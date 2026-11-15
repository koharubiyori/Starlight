package koharubiyori.starlight.component.commonDialog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import koharubiyori.starlight.component.styled.StyledCircularProgressIndicator

class CommonLoadingDialogProps(
  val title: String = "",
  val closeOnDismiss: Boolean = false,
  val onDismiss: (() -> Unit)? = null
)

class CommonLoadingDialogRef(
  private val _show: (props: CommonLoadingDialogProps?) -> Unit,
  val hide: () -> Unit
) {
  fun show(props: CommonLoadingDialogProps? = null) {
    _show(props)
  }

  fun showText(text: String) {
    _show(CommonLoadingDialogProps(text))
  }
}

@Composable
fun CommonLoadingDialog(
  ref: Ref<CommonLoadingDialogRef>
) {
  var visible by remember { mutableStateOf(false) }
  var currentProps by remember { mutableStateOf<CommonLoadingDialogProps?>(null) }

  fun show(props: CommonLoadingDialogProps? = null) {
    visible = true
    currentProps = props
  }

  fun hide() {
    visible = false
  }

  ref.value = remember {
    CommonLoadingDialogRef(
      _show = { show(it) },
      hide = { hide() }
    )
  }

  if (!visible) { return }

  BackHandler(
    onBack = { visible = false }
  )

  val props = currentProps ?: CommonLoadingDialogProps()
  CommonLoadingDialogUI(
    title = props.title,
    onDismiss = {
      if (props.closeOnDismiss) {
        visible = false
        props.onDismiss?.invoke()
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonLoadingDialogUI(
  title: String,
  onDismiss: () -> Unit,
) {
  val configuration = LocalConfiguration.current
  val dialogWidth = configuration.screenWidthDp * 0.7

  BasicAlertDialog(
    modifier = Modifier
      .width(dialogWidth.dp),
    content = {
      Surface(
        modifier = Modifier,
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation
      ) {
        Row(
          modifier = Modifier
            .padding(24.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          StyledCircularProgressIndicator()

          Text(
            modifier = Modifier
              .padding(start = 20.dp),
            text = "$title...",
            fontSize = 17.sp
          )
        }
      }

    },
    onDismissRequest = onDismiss
  )
}