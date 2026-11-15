/**
 * Created by Karim Mreisi.
 */

package koharubiyori.starlight.screen.streaming.util.input.virtual_controller;

import android.content.Context;

import koharubiyori.starlight.nvstream.input.ControllerPacket;

public class LeftAnalogStick extends AnalogStick {
    public LeftAnalogStick(final VirtualController controller, final Context context) {
        super(controller, context, EID_LS);

        addAnalogStickListener(new AnalogStick.AnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.leftStickX = (short) (x * 0x7FFE);
                inputContext.leftStickY = (short) (y * 0x7FFE);

                controller.sendControllerInputContext();
            }

            @Override
            public void onClick() {
            }

            @Override
            public void onDoubleClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= ControllerPacket.LS_CLK_FLAG;

                controller.sendControllerInputContext();
            }

            @Override
            public void onRevoke() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~ControllerPacket.LS_CLK_FLAG;

                controller.sendControllerInputContext();
            }
        });
    }
}
