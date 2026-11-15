package koharubiyori.starlight.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.node.Ref
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import koharubiyori.starlight.R
import koharubiyori.starlight.component.styled.StyledTextField
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@Composable
fun VerifiableOutlinedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  readOnly: Boolean = false,
  textStyle: TextStyle = LocalTextStyle.current,
  label: @Composable (() -> Unit)? = null,
  placeholder: @Composable (() -> Unit)? = null,
  leadingIcon: @Composable (() -> Unit)? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  prefix: @Composable (() -> Unit)? = null,
  suffix: @Composable (() -> Unit)? = null,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  singleLine: Boolean = false,
  maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
  minLines: Int = 1,
  interactionSource: MutableInteractionSource? = null,
  shape: Shape = OutlinedTextFieldDefaults.shape,
  colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),

  inputFieldRules: List<InputFieldRule> = emptyList(),
  ref: Ref<VerifiableTextFieldRef>? = null,
) {
  val coroutine = rememberCoroutineScope()
  val commonState = CommonState.remember(value, inputFieldRules, ref)

  OutlinedTextField(
    modifier = modifier
      .onFocusChanged {
        // The last value needs to be checked because the hook will be called during initialization
        if (commonState.wasFocused.value) coroutine.launch { commonState.validate() }
        commonState.wasFocused.value = it.isFocused
      }
      .then(modifier),
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    readOnly = readOnly,
    textStyle = textStyle,
    label = label,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    prefix = prefix,
    suffix = suffix,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    maxLines = maxLines,
    minLines = minLines,
    interactionSource = interactionSource,
    shape = shape,
    colors = colors,

    isError = commonState.hitRuleIndex.value > -1,
    supportingText = commonState.supportingText?.let { { Text(it) } }
  )
}

@Composable
fun VerifiableTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  readOnly: Boolean = false,
  textStyle: TextStyle = LocalTextStyle.current,
  label: @Composable (() -> Unit)? = null,
  placeholder: @Composable (() -> Unit)? = null,
  leadingIcon: @Composable (() -> Unit)? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  prefix: @Composable (() -> Unit)? = null,
  suffix: @Composable (() -> Unit)? = null,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  singleLine: Boolean = false,
  maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
  minLines: Int = 1,
  interactionSource: MutableInteractionSource? = null,
  shape: Shape = TextFieldDefaults.shape,
  colors: TextFieldColors = TextFieldDefaults.colors(),

  inputFieldRules: List<InputFieldRule> = emptyList(),
  ref: Ref<VerifiableTextFieldRef>? = null,
) {
  val coroutine = rememberCoroutineScope()
  val commonState = CommonState.remember(value, inputFieldRules, ref)

  StyledTextField(
    modifier = modifier
      .onFocusChanged {
        // The last value needs to be checked because the hook will be called during initialization
        if (commonState.wasFocused.value) coroutine.launch { commonState.validate() }
        commonState.wasFocused.value = it.isFocused
      }
      .then(modifier),
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    readOnly = readOnly,
    textStyle = textStyle,
    label = label,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    prefix = prefix,
    suffix = suffix,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    maxLines = maxLines,
    minLines = minLines,
    interactionSource = interactionSource,
    shape = shape,
    colors = colors.copy(

    ),

    isError = commonState.hitRuleIndex.value > -1,
    supportingText = commonState.supportingText?.let { { Text(it) } }
  )
}

class VerifiableTextFieldRef(
  val validate: suspend () -> Boolean
)

private class CommonState private constructor(
  val value: String,
  val wasFocused: MutableState<Boolean>,
  val hitRuleIndex: MutableState<Int>,
  val supportingText: String?,
  val validate: suspend () -> Boolean
) {
  companion object {
    @Composable
    fun remember(
      value: String,
      inputFieldRules: List<InputFieldRule>,
      ref: Ref<VerifiableTextFieldRef>?,
    ): CommonState {
      val wasFocused = rememberSaveable { mutableStateOf(false) }
      val hitRuleIndex = rememberSaveable { mutableIntStateOf(-1) }
      val hitRule = inputFieldRules.getOrNull(hitRuleIndex.intValue)
      val supportingText =
        flow { emit(hitRule?.getHelpingText(value)) }.collectAsStateWithLifecycle(null).value

      suspend fun validate(): Boolean {
        hitRuleIndex.intValue = inputFieldRules.indexOfFirst { !it.test(value) }
        return hitRuleIndex.intValue == -1
      }

      SideEffect {
        ref?.value = VerifiableTextFieldRef(
          validate = { validate() }
        )
      }

      return remember(value, inputFieldRules, wasFocused, hitRuleIndex, supportingText) {
        CommonState(value, wasFocused, hitRuleIndex, supportingText, { validate() })
      }
    }
  }
}

class InputFieldRule(
  val required: Boolean = false,
  val validator: (suspend (String) -> Boolean)? = null,
  val emptyHelpingText: String,
  val mismatchHelpingText: String
) {
  suspend fun validate(text: String): HitType {
    if (text.trim().isEmpty()) return if (required) HitType.EMPTY else HitType.NONE
    if (validator?.invoke(text) == false) return HitType.MISMATCH
    return HitType.NONE
  }

  suspend fun test(text: String): Boolean {
    return validate(text) == HitType.NONE
  }

  suspend fun getHelpingText(text: String) = when (validate(text)) {
    HitType.EMPTY -> emptyHelpingText
    HitType.MISMATCH -> mismatchHelpingText
    HitType.NONE -> null
  }

  companion object {
    fun requiredOnly(emptyHelpingText: String, mismatchHelpingText: String) = listOf(
      InputFieldRule(
        required = true,
        emptyHelpingText = emptyHelpingText,
        mismatchHelpingText = mismatchHelpingText
      )
    )
  }

  enum class HitType {
    EMPTY, MISMATCH, NONE
  }
}


