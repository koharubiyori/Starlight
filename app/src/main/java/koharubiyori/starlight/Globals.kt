package koharubiyori.starlight

import koharubiyori.starlight.component.commonDialog.CommonAlertDialogRef
import koharubiyori.starlight.component.commonDialog.CommonLoadingDialogRef
import koharubiyori.starlight.component.commonDialog.CommonRadioDialogRef

// To place lateinit variables for using in global
object Globals {
  lateinit var commonAlertDialog: CommonAlertDialogRef
  lateinit var commonRadioDialog: CommonRadioDialogRef
  lateinit var commonLoadingDialog: CommonLoadingDialogRef
}
