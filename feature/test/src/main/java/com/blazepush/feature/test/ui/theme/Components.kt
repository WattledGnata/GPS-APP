package com.blazepush.feature.test.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 霓虹按钮样式
 */
@Composable
fun NeonButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: NeonButtonVariant = NeonButtonVariant.Primary,
    text: @Composable () -> Unit
) {
    val colors = when (variant) {
        NeonButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = NeonColors.ButtonBackground,
            contentColor = NeonColors.ButtonOnBackground,
            disabledContainerColor = NeonColors.ButtonBackgroundDisabled,
            disabledContentColor = NeonColors.ButtonOnBackgroundDisabled
        )
        NeonButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = NeonColors.Secondary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = NeonColors.OnSurfaceVariant
        )
        NeonButtonVariant.Accent -> ButtonDefaults.buttonColors(
            containerColor = NeonColors.Accent,
            contentColor = NeonColors.ButtonOnBackground,
            disabledContainerColor = NeonColors.ButtonBackgroundDisabled,
            disabledContentColor = NeonColors.ButtonOnBackgroundDisabled
        )
        NeonButtonVariant.Outline -> ButtonDefaults.outlinedButtonColors(
            contentColor = NeonColors.Primary,
            disabledContentColor = NeonColors.OnSurfaceVariant
        )
    }

    val border = when (variant) {
        NeonButtonVariant.Outline -> BorderStroke(1.dp, NeonColors.Primary)
        NeonButtonVariant.Secondary -> BorderStroke(1.dp, NeonColors.Secondary)
        else -> null
    }

    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        border = border,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            text()
        }
    }
}

enum class NeonButtonVariant {
    Primary, Secondary, Accent, Outline
}

/**
 * 霓虹渐变按钮
 */
@Composable
fun NeonGradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradientColors: List<Color> = NeonGradients.PrimaryGradient,
    text: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = NeonColors.ButtonOnBackground
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        val gradient = Brush.horizontalGradient(gradientColors)
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            text()
        }
    }
}

/**
 * 霓虹卡片
 */
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    variant: NeonCardVariant = NeonCardVariant.Default,
    content: @Composable () -> Unit
) {
    val backgroundColor = when (variant) {
        NeonCardVariant.Default -> NeonColors.CardBackground
        NeonCardVariant.Surface -> NeonColors.Surface
        NeonCardVariant.SurfaceVariant -> NeonColors.SurfaceVariant
    }

    val border = when (variant) {
        NeonCardVariant.SurfaceVariant -> BorderStroke(
            1.dp,
            NeonColors.Divider.copy(alpha = 0.5f)
        )
        else -> null
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            content()
        }
    }
}

enum class NeonCardVariant {
    Default, Surface, SurfaceVariant
}

/**
 * 霓虹输入框
 */
@Composable
fun NeonTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    error: String? = null,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = if (label != null) {
            { Text(label, color = NeonColors.OnSurfaceVariant) }
        } else null,
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = NeonColors.OnSurfaceVariant) }
        } else null,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        isError = error != null,
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeonColors.TextFieldBorderFocused,
            unfocusedBorderColor = NeonColors.TextFieldBorder,
            errorBorderColor = NeonColors.Error,
            focusedContainerColor = NeonColors.TextFieldBackground,
            unfocusedContainerColor = NeonColors.TextFieldBackground,
            errorContainerColor = NeonColors.TextFieldBackground,
            focusedTextColor = NeonColors.OnSurface,
            unfocusedTextColor = NeonColors.OnSurface,
            focusedLabelColor = NeonColors.Primary,
            unfocusedLabelColor = NeonColors.OnSurfaceVariant,
            errorLabelColor = NeonColors.Error,
            cursorColor = NeonColors.Primary
        ),
        textStyle = TextStyle(
            fontSize = 16.sp,
            color = NeonColors.OnSurface
        )
    )
}

/**
 * 霓虹表面状态栏
 */
@Composable
fun NeonSurfaceBadge(
    modifier: Modifier = Modifier,
    color: Color = NeonColors.Primary,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        ProvideTextStyle(
            TextStyle(
                color = color,
                fontSize = 12.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        ) {
            content()
        }
    }
}

@Composable
private fun Text(text: String, color: Color) {
    androidx.compose.material3.Text(
        text = text,
        color = color
    )
}
