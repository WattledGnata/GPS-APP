/*
 * ESP32 RaceChrono BLE DIY GPS using Ublox M9N
 * This code reads GPS data from Ublox M9N module and sends it via BLE for RaceChrono app
 * Sends GPS data at 25Hz frequency
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// RaceChrono BLE DIY Service UUID
#define SERVICE_UUID        "00001ff8-0000-1000-8000-00805f9b34fb"

// RaceChrono BLE DIY Characteristics UUIDs
#define GPS_MAIN_CHAR_UUID  "00000003-0000-1000-8000-00805f9b34fb"
#define GPS_TIME_CHAR_UUID  "00000004-0000-1000-8000-00805f9b34fb"

// UART Configuration for GPS Module
// GPS is now pre-configured to 115200 baud via u-center/bridge script
#define GPS_BAUD            115200
#define GPS_RX_PIN          13  // ESP32 RX2 pin (connect to GPS TX)
#define GPS_TX_PIN          12  // 
ESP32 TX2 pin (connect to GPS RX)

// BLE Server and Characteristics
BLEServer *pServer = NULL;
BLECharacteristic *pGpsMainCharacteristic = NULL;
BLECharacteristic *pGpsTimeCharacteristic = NULL;

// GPS data structure
struct GpsData {
  uint8_t syncBits;          // 3 bits sync counter
  int timeSinceHourStart;    // Time in milliseconds since hour start
  uint8_t fixQuality;        // 2 bits: 0=invalid, 1=GPS, 2=DGPS
  uint8_t satellites;        // 6 bits: number of satellites (0-63)
  int32_t latitude;          // Latitude in degrees * 10,000,000
  int32_t longitude;         // Longitude in degrees * 10,000,000
  int altitude;              // Encoded altitude
  int speed;                 // Encoded speed
  int bearing;               // Bearing in degrees * 100
  uint8_t hdop;              // Horizontal dilution of precision * 10
  uint8_t vdop;              // Vertical dilution of precision * 10
};

GpsData gpsData;

// Parsed GPS data from NMEA sentences
struct ParsedGpsData {
  double latitude;           // Latitude in degrees
  double longitude;          // Longitude in degrees
  float altitude;           // Altitude in meters
  float speed;              // Speed in km/h
  float bearing;            // Bearing in degrees
  int satellites;           // Number of satellites
  float hdop;               // Horizontal dilution of precision
  float vdop;               // Vertical dilution of precision
  uint8_t fixQuality;       // Fix quality
  bool hasFix;              // True if GPS has fix
  
  // Time and Date
  int year;                 // Year (20xx)
  int month;                // Month (1-12)
  int day;                  // Day (1-31)
  int hour;                 // Hour (0-23)
  int minute;               // Minute (0-59)
  int second;               // Second (0-59)
  int centisecond;          // Centisecond (0-99)
};

ParsedGpsData parsedGpsData;

// Timing variables
unsigned long lastSendTime = 0;
// 25Hz send interval to match RaceChrono expectations
// If GPS is 10Hz, some packets will be redundant, but that's fine for BLE protocol
const unsigned long sendInterval = 40; 

// NMEA parsing variables
String nmeaBuffer = "";

// Connection monitoring variables
unsigned long lastGpsDataTime = 0;
const unsigned long gpsTimeout = 5000; // 5 seconds timeout
bool gpsConnectionWarningSent = false;

// BLE Server Callbacks
class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      Serial.println("Device connected");
    };

    void onDisconnect(BLEServer* pServer) {
      Serial.println("Device disconnected");
      pServer->startAdvertising(); // Restart advertising
    }
};

// Initialize BLE
void initBLE() {
  BLEDevice::init("RaceChronoDIY");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);
  pGpsMainCharacteristic = pService->createCharacteristic(
                          GPS_MAIN_CHAR_UUID,
                          BLECharacteristic::PROPERTY_READ |
                          BLECharacteristic::PROPERTY_NOTIFY
                        );
  pGpsTimeCharacteristic = pService->createCharacteristic(
                          GPS_TIME_CHAR_UUID,
                          BLECharacteristic::PROPERTY_READ |
                          BLECharacteristic::PROPERTY_NOTIFY
                        );
  pGpsMainCharacteristic->addDescriptor(new BLE2902());
  pGpsTimeCharacteristic->addDescriptor(new BLE2902());
  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.println("BLE Server started");
}

// Initialize GPS module
void initGPS() {
  Serial.println("Initializing GPS...");
  
  // Initialize Serial at 115200 (Pre-configured via bridge tool)
  Serial1.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  delay(100);

  // Clear buffer
  while(Serial1.available()) Serial1.read();
  
  Serial.println("Ublox M9N connected at 115200 baud (25Hz High Performance)");
}

// Parse NMEA GGA sentence
void parseGGA(String sentence) {
  int commaIndex = 0;
  int fieldIndex = 0;
  String fields[15];
  
  for (int i = 0; i < sentence.length(); i++) {
    if (sentence[i] == ',') {
      fields[fieldIndex] = sentence.substring(commaIndex + 1, i);
      fieldIndex++;
      commaIndex = i;
    }
  }
  fields[fieldIndex] = sentence.substring(commaIndex + 1);
  
  if (fieldIndex >= 14) {
    // Time (HHMMSS.SS)
    if (fields[1] != "") {
      parsedGpsData.hour = fields[1].substring(0, 2).toInt();
      parsedGpsData.minute = fields[1].substring(2, 4).toInt();
      parsedGpsData.second = fields[1].substring(4, 6).toInt();
      parsedGpsData.centisecond = fields[1].substring(7, 9).toInt(); // .SS part
    }

    parsedGpsData.fixQuality = fields[6].toInt();
    parsedGpsData.hasFix = (parsedGpsData.fixQuality >= 1);
    parsedGpsData.satellites = fields[7].toInt();
    parsedGpsData.hdop = fields[8].toFloat();
    parsedGpsData.altitude = fields[9].toFloat();
    
    // Also parse Latitude/Longitude from GGA
    if (fields[2] != "" && fields[3] != "") {
      double latDeg = fields[2].substring(0, 2).toDouble();
      double latMin = fields[2].substring(2).toDouble();
      parsedGpsData.latitude = latDeg + (latMin / 60.0);
      if (fields[3] == "S") parsedGpsData.latitude = -parsedGpsData.latitude;
    }
    
    if (fields[4] != "" && fields[5] != "") {
      double lonDeg = fields[4].substring(0, 3).toDouble();
      double lonMin = fields[4].substring(3).toDouble();
      parsedGpsData.longitude = lonDeg + (lonMin / 60.0);
      if (fields[5] == "W") parsedGpsData.longitude = -parsedGpsData.longitude;
    }
  }
}

// Parse NMEA RMC sentence
void parseRMC(String sentence) {
  int commaIndex = 0;
  int fieldIndex = 0;
  String fields[15];
  
  for (int i = 0; i < sentence.length(); i++) {
    if (sentence[i] == ',') {
      fields[fieldIndex] = sentence.substring(commaIndex + 1, i);
      fieldIndex++;
      commaIndex = i;
    }
  }
  fields[fieldIndex] = sentence.substring(commaIndex + 1);
  
  if (fieldIndex >= 12) {
    // Time (HHMMSS.SS) - Field 1
    if (fields[1] != "") {
      parsedGpsData.hour = fields[1].substring(0, 2).toInt();
      parsedGpsData.minute = fields[1].substring(2, 4).toInt();
      parsedGpsData.second = fields[1].substring(4, 6).toInt();
      parsedGpsData.centisecond = fields[1].substring(7, 9).toInt(); 
    }

    // Latitude - Field 3, 4
    if (fields[3] != "" && fields[4] != "") {
      double latDeg = fields[3].substring(0, 2).toDouble();
      double latMin = fields[3].substring(2).toDouble();
      parsedGpsData.latitude = latDeg + (latMin / 60.0);
      if (fields[4] == "S") parsedGpsData.latitude = -parsedGpsData.latitude;
    }
    
    // Longitude - Field 5, 6
    if (fields[5] != "" && fields[6] != "") {
      double lonDeg = fields[5].substring(0, 3).toDouble();
      double lonMin = fields[5].substring(3).toDouble();
      parsedGpsData.longitude = lonDeg + (lonMin / 60.0);
      if (fields[6] == "W") parsedGpsData.longitude = -parsedGpsData.longitude;
    }
    
    // Speed (knots to km/h) - Field 7
    if (fields[7] != "") {
      parsedGpsData.speed = fields[7].toFloat() * 1.852;
    }
    
    // Bearing - Field 8
    if (fields[8] != "") {
      parsedGpsData.bearing = fields[8].toFloat();
    }

    // Date (DDMMYY) - Field 9
    if (fields[9] != "" && fields[9].length() == 6) {
      parsedGpsData.day = fields[9].substring(0, 2).toInt();
      parsedGpsData.month = fields[9].substring(2, 4).toInt();
      parsedGpsData.year = fields[9].substring(4, 6).toInt() + 2000;
    }
  }
}

// Process incoming NMEA data
void processNmeaData() {
  while (Serial1.available()) {
    char c = Serial1.read();
    
    if (c == '$') {
      nmeaBuffer = ""; // Reset buffer at start of sentence
    } 
    else if (c == '\n') { // End of sentence
      if (nmeaBuffer.length() > 0) {
        lastGpsDataTime = millis(); // Mark as data received
        gpsConnectionWarningSent = false;

        if (nmeaBuffer.startsWith("GPGGA") || nmeaBuffer.startsWith("GNGGA")) {
          parseGGA(nmeaBuffer);
        } else if (nmeaBuffer.startsWith("GPRMC") || nmeaBuffer.startsWith("GNRMC")) {
          parseRMC(nmeaBuffer);
        }
      }
      nmeaBuffer = "";
    } 
    else if (c != '\r') { // Ignore carriage return
      nmeaBuffer += c;
    }
  }
}

void updateGpsData() {
  // Use parsed GPS time instead of system time
  int milliseconds = parsedGpsData.centisecond * 10; 
  gpsData.timeSinceHourStart = (parsedGpsData.minute * 30000) + (parsedGpsData.second * 500) + (milliseconds / 2);
  
  static int previousHour = -1;
  if (previousHour != parsedGpsData.hour) {
    if (previousHour != -1) { // Don't increment on first run
        gpsData.syncBits = (gpsData.syncBits + 1) & 0x7;
    }
    previousHour = parsedGpsData.hour;
  }
  
  gpsData.fixQuality = parsedGpsData.fixQuality;
  gpsData.satellites = parsedGpsData.satellites;
  
  // Encode data for RaceChrono format
  gpsData.latitude = (int32_t)(parsedGpsData.latitude * 10000000.0);
  gpsData.longitude = (int32_t)(parsedGpsData.longitude * 10000000.0);
  
  if (parsedGpsData.altitude < 6053.5) {
    gpsData.altitude = ((int)((parsedGpsData.altitude + 500.0) * 10.0)) & 0x7FFF;
  } else {
    gpsData.altitude = (((int)(parsedGpsData.altitude + 500.0)) & 0x7FFF) | 0x8000;
  }
  
  if (parsedGpsData.speed < 655.35) {
    gpsData.speed = ((int)(parsedGpsData.speed * 100.0)) & 0x7FFF;
  } else {
    gpsData.speed = (((int)(parsedGpsData.speed * 10.0)) & 0x7FFF) | 0x8000;
  }
  
  gpsData.bearing = (int)(parsedGpsData.bearing * 100.0);
  gpsData.hdop = (uint8_t)(parsedGpsData.hdop * 10.0);
  gpsData.vdop = (uint8_t)(parsedGpsData.vdop * 10.0);
  
  if (gpsData.hdop > 255) gpsData.hdop = 255;
  if (gpsData.vdop > 255) gpsData.vdop = 255;
}

void sendGpsData() {
  updateGpsData();
  
  // Calculate DateAndHour from parsed GPS date
  // 21 bits = (Year-2000)*8928 + (Month-1)*744 + (Day-1)*24 + Hour
  int yearOffset = (parsedGpsData.year > 2000) ? (parsedGpsData.year - 2000) : 0;
  int monthOffset = (parsedGpsData.month > 0) ? (parsedGpsData.month - 1) : 0;
  int dayOffset = (parsedGpsData.day > 0) ? (parsedGpsData.day - 1) : 0;
  
  int dateAndHour = yearOffset * 8928 + monthOffset * 744 + dayOffset * 24 + parsedGpsData.hour;
  
  uint8_t gpsMainData[20] = {0};
  
  gpsMainData[0] = ((gpsData.syncBits & 0x7) << 5) | ((gpsData.timeSinceHourStart >> 16) & 0x1F);
  gpsMainData[1] = (gpsData.timeSinceHourStart >> 8) & 0xFF;
  gpsMainData[2] = gpsData.timeSinceHourStart & 0xFF;
  
  gpsMainData[3] = ((gpsData.fixQuality & 0x3) << 6) | (gpsData.satellites & 0x3F);
  
  gpsMainData[4] = (gpsData.latitude >> 24) & 0xFF;
  gpsMainData[5] = (gpsData.latitude >> 16) & 0xFF;
  gpsMainData[6] = (gpsData.latitude >> 8) & 0xFF;
  gpsMainData[7] = gpsData.latitude & 0xFF;
  
  gpsMainData[8] = (gpsData.longitude >> 24) & 0xFF;
  gpsMainData[9] = (gpsData.longitude >> 16) & 0xFF;
  gpsMainData[10] = (gpsData.longitude >> 8) & 0xFF;
  gpsMainData[11] = gpsData.longitude & 0xFF;
  
  // Altitude (2 bytes, big endian)
  gpsMainData[12] = (gpsData.altitude >> 8) & 0xFF;
  gpsMainData[13] = gpsData.altitude & 0xFF;
  
  // Speed (2 bytes, big endian)
  gpsMainData[14] = (gpsData.speed >> 8) & 0xFF;
  gpsMainData[15] = gpsData.speed & 0xFF;
  
  // Bearing (2 bytes, big endian)
  gpsMainData[16] = (gpsData.bearing >> 8) & 0xFF;
  gpsMainData[17] = gpsData.bearing & 0xFF;
  
  gpsMainData[18] = gpsData.hdop;
  gpsMainData[19] = gpsData.vdop;
  
  pGpsMainCharacteristic->setValue(gpsMainData, sizeof(gpsMainData));
  pGpsMainCharacteristic->notify();
  
  uint8_t gpsTimeData[3] = {0};
  gpsTimeData[0] = ((gpsData.syncBits & 0x7) << 5) | ((dateAndHour >> 16) & 0x1F);
  gpsTimeData[1] = (dateAndHour >> 8) & 0xFF;
  gpsTimeData[2] = dateAndHour & 0xFF;
  
  pGpsTimeCharacteristic->setValue(gpsTimeData, sizeof(gpsTimeData));
  pGpsTimeCharacteristic->notify();
}

void setup() {
  Serial.begin(115200);
  Serial.println("Starting ESP32 GPS M9N (Stable Mode)...");
  
  initGPS();
  initBLE();
  
  // Initialize GPS data defaults
  parsedGpsData.latitude = 0.0;
  parsedGpsData.longitude = 0.0;
  parsedGpsData.hasFix = false;
  parsedGpsData.fixQuality = 0;
  // Set default date to avoid 2204 if no GPS lock
  parsedGpsData.year = 2024;
  parsedGpsData.month = 1;
  parsedGpsData.day = 1;
}

void loop() {
  processNmeaData();
  
  unsigned long currentTime = millis();
  
  // Connection timeout check
  if (currentTime - lastGpsDataTime > gpsTimeout && !gpsConnectionWarningSent) {
    Serial.println("WARNING: No GPS data received!");
    gpsConnectionWarningSent = true;
  }
  
  // Send data at 25Hz
  if (currentTime - lastSendTime >= sendInterval) {
    lastSendTime = currentTime;
    sendGpsData();
  }
  
  // No delay here to process serial buffer as fast as possible
}
