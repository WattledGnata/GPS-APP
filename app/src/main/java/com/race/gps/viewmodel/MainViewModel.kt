package com.race.gps.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.data.model.AccelerationDataPoint
import com.race.gps.data.model.CarModel
import com.race.gps.data.model.TestRecord
import com.race.gps.data.repository.CarModelRepository
import com.race.gps.data.repository.TestRecordRepository
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "RaceChronoGPS"
    }
    
    private val testRecordRepository = TestRecordRepository(application)
    private val carModelRepository = CarModelRepository(application)

    // Test records
    private val _testRecords = MutableStateFlow<List<TestRecord>>(emptyList())
    val testRecords: StateFlow<List<TestRecord>> = _testRecords

    // Car models
    private val _carModels = MutableStateFlow<List<CarModel>>(emptyList())
    val carModels: StateFlow<List<CarModel>> = _carModels
    
    init {
        // Load initial test records and car models
        loadTestRecords()
        loadCarModels()
        
        // Observe test records flow for real-time updates
        viewModelScope.launch {
            testRecordRepository.testRecordsFlow.collect {
                _testRecords.value = it
                Log.d(TAG, "Test records updated via flow: ${it.size} records")
            }
        }
    }
    
    // Test state management
    private val _currentSpeed = MutableStateFlow(0.0)
    val currentSpeed: StateFlow<Double> = _currentSpeed
    
    private val _isTestRunning = MutableStateFlow(false)
    val isTestRunning: StateFlow<Boolean> = _isTestRunning
    
    private val _testResult = MutableStateFlow("Not Started")
    val testResult: StateFlow<String> = _testResult
    
    private val _isTestReady = MutableStateFlow(false)
    val isTestReady: StateFlow<Boolean> = _isTestReady
    
    // Acceleration data collection
    private val _accelerationDataPoints = MutableStateFlow<List<AccelerationDataPoint>>(emptyList())
    val accelerationDataPoints: StateFlow<List<AccelerationDataPoint>> = _accelerationDataPoints
    
    // Test states
    enum class TestState {
        WAITING_FOR_START_SPEED, // 等待达到起始速度
        RUNNING, // 测试进行中
        COMPLETED_SUCCESS, // 测试成功完成
        COMPLETED_FAILURE // 测试失败
    }
    
    // Current test parameters (dynamically determined from test type)
    private var currentStartSpeed = 0.0 // 当前测试起始速度（km/h）
    private var currentTargetSpeed = 0.0 // 当前测试目标速度（km/h）
    
    // Test timing using GPS clock
    private var testStartTimeGps = 0L
    private var testEndTimeGps = 0L
    private var isSpeedThresholdReached = false
    private var currentTestType = ""
    private var currentCarModel: CarModel? = null
    private val accelerationDataPointsList = mutableListOf<AccelerationDataPoint>()
    private var testState = TestState.WAITING_FOR_START_SPEED // 初始状态：等待起始速度
    private var isTestStartedFromValidSpeed = false // 是否从有效起始速度开始测试
    
    // Method to update GPS speed
    fun updateCurrentSpeed(speed: Double) {
        _currentSpeed.value = speed
        
        // Only process test logic if test is running
        if (_isTestRunning.value == true) {
            when (testState) {
                TestState.WAITING_FOR_START_SPEED -> {
                    // 等待达到起始速度
                    // 逻辑修改：
                    // 对于0起步测试（0-100）：
                    // 1. 如果当前速度在 0-5km/h 之间，我们认为是“静止或准静止”状态，保持 WAITING
                    // 2. 只有当速度 *超过* 了一定阈值（比如 > 1.0 km/h），我们才认为车辆真正起步了，开始计时。
                    //    但是，为了精确，我们应该把计时点回溯到速度刚刚开始上升的那一刻？
                    //    简化版：只要当前速度 > 0.5 且 < 5.0，就立即开始。
                    //    不，更科学的逻辑是：用户点击开始后，状态是WAITING。
                    //    此时用户是静止的（或者漂移中）。
                    //    只有当速度 *开始增加* 并且 *超过* 某个阈值（比如1km/h）时，才触发 START。
                    
                    if (currentStartSpeed <= 5.0) {
                        // 0-xxx 测试模式
                        // 只有当速度大于 1.0km/h 时才触发开始
                        // 这样避免了 0.0 -> 0.1 的漂移误触发
                        // 同时也不需要必须完全静止在 0.0
                        if (speed > 1.0) {
                            testState = TestState.RUNNING
                            testStartTimeGps = System.currentTimeMillis()
                            isTestStartedFromValidSpeed = true
                            _testResult.value = "Running..."
                            Log.d(TAG, "Test started (0-xxx): Speed > 1.0 ($speed km/h)")
                        }
                    } else {
                        // 60-160, 100-200 等区间测试
                        // 需要先进入起始速度区间（例如 58-62），然后再加速离开区间
                        // 这里简化处理：只要进入区间就算开始（Rolling Start）
                        val speedRange = currentStartSpeed - 3.0..currentStartSpeed + 3.0
                        if (speed in speedRange) {
                            testState = TestState.RUNNING
                            testStartTimeGps = System.currentTimeMillis()
                            isTestStartedFromValidSpeed = true
                            _testResult.value = "Running..."
                            Log.d(TAG, "Test started (Rolling): $speed km/h")
                        }
                    }
                }
                TestState.RUNNING -> {
                    // 测试进行中，记录加速度数据点
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = (currentTime - testStartTimeGps) / 1000.0 // Convert to seconds
                    val dataPoint = AccelerationDataPoint(time = elapsedTime, speed = speed)
                    accelerationDataPointsList.add(dataPoint)
                    _accelerationDataPoints.value = accelerationDataPointsList.toList()
                    
                    // 检查是否达到目标速度（所有测试类型通用）
                    if (!isSpeedThresholdReached && speed >= currentTargetSpeed) {
                        isSpeedThresholdReached = true
                        testState = TestState.COMPLETED_SUCCESS
                        stopTest()
                        Log.d(TAG, "Test completed successfully, reached target speed: $speed km/h")
                    }
                    
                    // 检查是否测试失败（例如速度下降太多）
                    // 对于不同测试类型，失败条件不同：
                    // - 0-xxx测试：速度从较高值下降到接近0
                    // - xx-yy测试：速度下降到低于起始速度
                    
                    // 只有当速度已经上升到一定程度后，才检查失败条件
                    val isSpeedIncreasing = accelerationDataPointsList.size > 0 && 
                            speed >= accelerationDataPointsList.last().speed
                    
                    // 对于0-xxx测试，只有当速度至少上升到20km/h后，才检查是否下降到10km/h以下
                    // 对于xx-yy测试，只有当速度至少上升到起始速度+10km/h后，才检查是否下降到起始速度-5km/h以下
                    val isSpeedHighEnough = if (currentStartSpeed <= 5.0) {
                        // 0-xxx测试
                        speed > 20.0
                    } else {
                        // xx-yy测试
                        speed > currentStartSpeed + 10.0
                    }
                    
                    // 计算失败阈值
                    val failureSpeedThreshold = if (currentStartSpeed <= 5.0) {
                        // 0-xxx测试，允许下降到10km/h
                        10.0
                    } else {
                        // xx-yy测试，允许下降到起始速度-5km/h
                        currentStartSpeed - 5.0
                    }
                    
                    // 只有当速度足够高且在下降时，才判定为测试失败
                    if (accelerationDataPointsList.size > 5 && 
                        isSpeedHighEnough && 
                        !isSpeedIncreasing && 
                        speed < failureSpeedThreshold) {
                        // 如果已经记录了5个数据点，速度足够高，且正在下降到阈值以下，说明测试失败
                        testState = TestState.COMPLETED_FAILURE
                        stopTest()
                        Log.d(TAG, "Test failed, speed dropped to: $speed km/h, failure threshold: $failureSpeedThreshold")
                    }
                }
                else -> {
                    // 测试已经完成，不再处理速度更新
                }
            }
        }
    }
    
    // Method to start test
    fun startTest(testType: String, carModel: CarModel) {
        Log.d(TAG, "Starting test: $testType with car model: ${carModel.name}")
        
        // Reset test state
        currentTestType = testType
        currentCarModel = carModel
        testStartTimeGps = 0L // 初始化为0，等待达到起始速度后再开始计时
        testEndTimeGps = 0L
        isSpeedThresholdReached = false
        accelerationDataPointsList.clear()
        testState = TestState.WAITING_FOR_START_SPEED // 初始状态：等待起始速度
        isTestStartedFromValidSpeed = false
        
        // 根据测试类型设置起始速度和目标速度
        val (startSpeed, targetSpeed) = parseTestType(testType)
        currentStartSpeed = startSpeed
        currentTargetSpeed = targetSpeed
        
        // Update StateFlow
        _isTestRunning.value = true
        _testResult.value = "等待起始速度..."
        _accelerationDataPoints.value = emptyList()
        Log.d(TAG, "Test initialized, waiting for start speed: ${currentStartSpeed}km/h, target speed: ${currentTargetSpeed}km/h")
    }
    
    /**
     * 解析测试类型字符串，提取起始速度和目标速度
     * 支持格式："0-100km/h", "60-160km/h", "0-200km/h"
     */
    private fun parseTestType(testType: String): Pair<Double, Double> {
        // 默认值：0-100km/h
        var startSpeed = 0.0
        var targetSpeed = 100.0
        
        try {
            // 移除"km/h"后缀
            val speedPart = testType.replace("km/h", "").trim()
            // 分割起始和目标速度
            val speeds = speedPart.split("-")
            if (speeds.size == 2) {
                startSpeed = speeds[0].toDouble()
                targetSpeed = speeds[1].toDouble()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse test type: $testType", e)
        }
        
        return Pair(startSpeed, targetSpeed)
    }
    
    // Method to stop test
    fun stopTest() {
        Log.d(TAG, "Stopping test: $currentTestType")
        
        testEndTimeGps = System.currentTimeMillis()
        _isTestRunning.value = false
        
        // Calculate test result
        val testResult = calculateTestResult()
        _testResult.value = testResult
        
        // Save test record if we have a valid result
        if (testResult != "No valid data" && currentCarModel != null) {
            saveTestRecord(testResult)
        }
    }
    
    // Method to calculate test result
    private fun calculateTestResult(): String {
        when (testState) {
            TestState.COMPLETED_SUCCESS -> {
                // 测试成功，计算耗时
                if (accelerationDataPointsList.isNotEmpty() && testEndTimeGps > testStartTimeGps) {
                    val elapsedTime = (testEndTimeGps - testStartTimeGps) / 1000.0 // Convert to seconds
                    return String.format("%.2f秒", elapsedTime)
                }
            }
            TestState.COMPLETED_FAILURE -> {
                // 测试失败
                return "测试失败"
            }
            TestState.WAITING_FOR_START_SPEED -> {
                // 测试未开始，等待起始速度
                return "测试未开始"
            }
            else -> {
                // 其他情况
            }
        }
        
        // 如果没有有效数据
        return "无效测试数据"
    }
    
    // Method to save test record
    private fun saveTestRecord(result: String) {
        val carModel = currentCarModel ?: return
        
        // 只有测试成功完成才保存记录
        if (testState == TestState.COMPLETED_SUCCESS) {
            val testRecord = TestRecord(
                testType = currentTestType,
                carModel = carModel.name,
                deviceName = "RaceChrono GPS",
                deviceAddress = "",
                result = result,
                accelerationData = accelerationDataPointsList.toList()
            )
            
            addTestRecord(testRecord)
            Log.d(TAG, "Test record saved: $testRecord")
        } else {
            Log.d(TAG, "Test record not saved because test state is: $testState")
        }
    }
    
    // Method to update test ready state
    fun updateTestReady(isReady: Boolean) {
        _isTestReady.value = isReady
    }

    fun loadTestRecords() {
        Log.d(TAG, "Loading test records...")
        viewModelScope.launch(Dispatchers.IO) {
            val records = testRecordRepository.getSavedTestRecords()
            Log.d(TAG, "Loaded ${records.size} test records")
            withContext(Dispatchers.Main) {
                _testRecords.value = records
            }
        }
    }

    fun loadCarModels() {
        Log.d(TAG, "Loading car models...")
        viewModelScope.launch(Dispatchers.IO) {
            val carModels = carModelRepository.getSavedCarModels()
            Log.d(TAG, "Loaded ${carModels.size} car models")
            withContext(Dispatchers.Main) {
                _carModels.value = carModels
            }
        }
    }

    fun addTestRecord(testRecord: TestRecord) {
        Log.d(TAG, "Adding test record: $testRecord")
        viewModelScope.launch(Dispatchers.IO) {
            testRecordRepository.addTestRecord(testRecord)
            Log.d(TAG, "Test record added successfully")
            loadTestRecords()
        }
    }

    fun removeTestRecord(testRecord: TestRecord) {
        Log.d(TAG, "Removing test record: $testRecord")
        viewModelScope.launch(Dispatchers.IO) {
            testRecordRepository.removeTestRecord(testRecord)
            Log.d(TAG, "Test record removed successfully")
            loadTestRecords()
        }
    }

    fun addCarModel(carModel: CarModel) {
        Log.d(TAG, "Adding car model: $carModel")
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.addCarModel(carModel)
            Log.d(TAG, "Car model added successfully")
            loadCarModels()
        }
    }

    fun updateCarModel(carModel: CarModel) {
        Log.d(TAG, "Updating car model: $carModel")
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.updateCarModel(carModel)
            Log.d(TAG, "Car model updated successfully")
            loadCarModels()
        }
    }

    fun removeCarModel(carModel: CarModel) {
        Log.d(TAG, "Removing car model: $carModel")
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.removeCarModel(carModel)
            Log.d(TAG, "Car model removed successfully")
            loadCarModels()
        }
    }
}