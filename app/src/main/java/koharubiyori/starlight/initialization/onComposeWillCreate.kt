package koharubiyori.starlight.initialization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.node.Ref
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import koharubiyori.starlight.Globals
import koharubiyori.starlight.component.commonDialog.CommonAlertDialog
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogRef
import koharubiyori.starlight.component.commonDialog.CommonLoadingDialog
import koharubiyori.starlight.component.commonDialog.CommonLoadingDialogRef
import koharubiyori.starlight.component.commonDialog.CommonRadioDialog
import koharubiyori.starlight.component.commonDialog.CommonRadioDialogRef

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnComposeWillCreate(
  content: @Composable (NavHostController) -> Unit
) {
  val navController = rememberNavController()

  val commonAlertDialogRef = remember { Ref<CommonAlertDialogRef>() }
  val commonRadioDialogRef = remember { Ref<CommonRadioDialogRef>() }
  val commonLoadingDialogRef = remember { Ref<CommonLoadingDialogRef>() }

  LaunchedEffect(Unit) {
    Globals.commonAlertDialog = commonAlertDialogRef.value!!
    Globals.commonRadioDialog = commonRadioDialogRef.value!!
    Globals.commonLoadingDialog = commonLoadingDialogRef.value!!

    onComposeCreated()
  }

  content(navController)

  CommonRadioDialog(ref = commonRadioDialogRef)
  CommonAlertDialog(ref = commonAlertDialogRef)
  CommonLoadingDialog(ref = commonLoadingDialogRef)
}

//@Composable
//private fun GlRendererGetter() {
//
//}

