/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.controlwear.joystickdemo;

import io.github.controlwear.joystickdemo.inputmanagercompat.InputManagerCompat;
import io.github.controlwear.joystickdemo.inputmanagercompat.InputManagerCompat.InputDeviceListener;
import io.github.controlwear.virtual.joystick.android.JoystickView;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.os.Build;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/*
 * A trivial joystick based physics game to demonstrate joystick handling. If
 * the game controller has a vibrator, then it is used to provide feedback when
 * a bullet is fired or the ship crashes into an obstacle. Otherwise, the system
 * vibrator is used for that purpose.
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
public class UdpJoystick extends View implements InputDeviceListener {

    private static final int DPAD_STATE_LEFT = 1 << 0;
    private static final int DPAD_STATE_RIGHT = 1 << 1;
    private static final int DPAD_STATE_UP = 1 << 2;
    private static final int DPAD_STATE_DOWN = 1 << 3;

    private final SparseArray<RealJoystick> mJoysticks;

    private long mLastStepTime;
    private final InputManagerCompat mInputManager;
    private String IP;

    public UdpJoystick(Context context, AttributeSet attrs, String IP) {
        super(context, attrs);

        mJoysticks = new SparseArray<RealJoystick>();

        setFocusable(true);
        setFocusableInTouchMode(true);

        mInputManager = InputManagerCompat.Factory.getInputManager(this.getContext());
        mInputManager.registerInputDeviceListener(this, null);
    }

    // Iterate through the input devices, looking for controllers. Create a ship
    // for every device that reports itself as a gamepad or joystick.
    public ArrayList getGameControllerIds() {
        ArrayList gameControllerDeviceIds = new ArrayList();
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice dev = InputDevice.getDevice(deviceId);
            int sources = dev.getSources();
    
            // Verify that the device has gamepad buttons, control sticks, or both.
            if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                    || ((sources & InputDevice.SOURCE_JOYSTICK)
                    == InputDevice.SOURCE_JOYSTICK)) {
                // This device is a game controller. Store its device ID.
                if (!gameControllerDeviceIds.contains(deviceId)) {
                    gameControllerDeviceIds.add(deviceId);
                }
            }
        }
        return gameControllerDeviceIds;
    }

    private void removeJoystickForID(int shipID) {
        mJoysticks.remove(shipID);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int deviceId = event.getDeviceId();
        if (deviceId != -1) {
            RealJoystick joystick = getJoystickForId(deviceId);
            if (joystick.onKeyDown(keyCode, event)) {
                step(event.getEventTime());
                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int deviceId = event.getDeviceId();
        if (deviceId != -1) {
            RealJoystick joystick = getJoystickForId(deviceId);
            if (joystick.onKeyUp(keyCode, event)) {
                step(event.getEventTime());
                return true;
            }
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        // Turn on and off animations based on the window focus.
        // Alternately, we could update the game state using the Activity
        // onResume()
        // and onPause() lifecycle events.
        if (hasWindowFocus) {
            mLastStepTime = SystemClock.uptimeMillis();
            mInputManager.onResume();
        } else {
            int numShips = mJoysticks.size();
            for (int i = 0; i < numShips; i++) {
                RealJoystick currentJoystick = mJoysticks.valueAt(i);
                if (currentJoystick != null) {
                    currentJoystick.setHeading(0, 0);
                    currentJoystick.mDPadState = 0;
                }
            }
            mInputManager.onPause();
        }

        super.onWindowFocusChanged(hasWindowFocus);
    }

    private void step(long currentStepTime) {
        float tau = (currentStepTime - mLastStepTime) * 0.001f;
        mLastStepTime = currentStepTime;

        // Move the ships
        int numJoysticks = mJoysticks.size();
        for (int i = 0; i < numJoysticks; i++) {
            RealJoystick currentJoystick = mJoysticks.valueAt(i);
            if (currentJoystick != null) {
                currentJoystick.accelerate(IP);
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        mInputManager.onGenericMotionEvent(event);

        // Check that the event came from a joystick or gamepad since a generic
        // motion event could be almost anything. API level 18 adds the useful
        // event.isFromSource() helper function.
        int eventSource = event.getSource();
        if ((((eventSource & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                ((eventSource & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK))
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            int id = event.getDeviceId();
            if (-1 != id) {
                RealJoystick joystick = getJoystickForId(id);
                if (joystick.onGenericMotionEvent(event)) {
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private static float getCenteredAxis(MotionEvent event, InputDevice device,
            int axis, int historyPos) {
        final InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range != null) {
            final float flat = range.getFlat();
            final float value = historyPos < 0 ? event.getAxisValue(axis)
                    : event.getHistoricalAxisValue(axis, historyPos);

            // Ignore axis values that are within the 'flat' region of the
            // joystick axis center.
            // A joystick at rest does not always report an absolute position of
            // (0,0).
            if (Math.abs(value) > flat) {
                return value;
            }
        }
        return 0;
    }

    private static float pythag(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private RealJoystick getJoystickForId(int shipID) {
        RealJoystick currentJoystick = mJoysticks.get(shipID);
        if (null == currentJoystick) {

            // do we know something about this ship already?
            InputDevice dev = InputDevice.getDevice(shipID);

            mJoysticks.append(shipID, currentJoystick);
            currentJoystick.setInputDevice(dev);

        }
        return currentJoystick;
    }

    private class RealJoystick {
        // The current device that is controlling the ship
        private InputDevice mInputDevice;

        private float mHeadingX;
        private float mHeadingY;
        private float mHeadingAngle;
        private float mHeadingMagnitude;

        private int mDPadState;


        public boolean onKeyUp(int keyCode, KeyEvent event) {

            // Handle keys going up.
            boolean handled = false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    setHeadingX(0);
                    mDPadState &= ~DPAD_STATE_LEFT;
                    handled = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    setHeadingX(0);
                    mDPadState &= ~DPAD_STATE_RIGHT;
                    handled = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    setHeadingY(0);
                    mDPadState &= ~DPAD_STATE_UP;
                    handled = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    setHeadingY(0);
                    mDPadState &= ~DPAD_STATE_DOWN;
                    handled = true;
                    break;
                default:
                    break;
            }
            return handled;
        }

        public boolean onKeyDown(int keyCode, KeyEvent event) {
            // Handle DPad keys and fire button on initial down but not on
            // auto-repeat.
            boolean handled = false;
            if (event.getRepeatCount() == 0) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        setHeadingX(-1);
                        mDPadState |= DPAD_STATE_LEFT;
                        handled = true;
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        setHeadingX(1);
                        mDPadState |= DPAD_STATE_RIGHT;
                        handled = true;
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        setHeadingY(-1);
                        mDPadState |= DPAD_STATE_UP;
                        handled = true;
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        setHeadingY(1);
                        mDPadState |= DPAD_STATE_DOWN;
                        handled = true;
                        break;
                    default:
                        break;
                }
            }
            return handled;
        }

        /**
         * The ship directly handles joystick input.
         *
         * @param event
         * @param historyPos
         */
        private void processJoystickInput(MotionEvent event, int historyPos) {
            // Get joystick position.
            // Many game pads with two joysticks report the position of the
            // second
            // joystick
            // using the Z and RZ axes so we also handle those.
            // In a real game, we would allow the currentuser to configure the axes
            // manually.
            if (null == mInputDevice) {
                mInputDevice = event.getDevice();
            }
            float x = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_X, historyPos);
            if (x == 0) {
                x = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_HAT_X, historyPos);
            }
            if (x == 0) {
                x = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_Z, historyPos);
            }

            float y = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_Y, historyPos);
            if (y == 0) {
                y = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_HAT_Y, historyPos);
            }
            if (y == 0) {
                y = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_RZ, historyPos);
            }

            // Set the ship heading.
            setHeading(x, y);
            UdpJoystick.this.step(historyPos < 0 ? event.getEventTime() : event
                    .getHistoricalEventTime(historyPos));
        }

        public boolean onGenericMotionEvent(MotionEvent event) {
            if (0 == mDPadState) {
                // Process all historical movement samples in the batch.
                final int historySize = event.getHistorySize();
                for (int i = 0; i < historySize; i++) {
                    processJoystickInput(event, i);
                }

                // Process the current movement sample in the batch.
                processJoystickInput(event, -1);
            }
            return true;
        }


        /**
         * Set the game controller to be used to control the ship.
         *
         * @param dev the input device that will be controlling the ship
         */
        public void setInputDevice(InputDevice dev) {
            mInputDevice = dev;
        }

        /**
         * Sets the X component of the joystick heading value, defined by the
         * platform as being from -1.0 (left) to 1.0 (right). This function is
         * generally used to change the heading in response to a button-style
         * DPAD event.
         *
         * @param x the float x component of the joystick heading value
         */
        public void setHeadingX(float x) {
            mHeadingX = x;
            updateHeading();
        }

        /**
         * Sets the Y component of the joystick heading value, defined by the
         * platform as being from -1.0 (top) to 1.0 (bottom). This function is
         * generally used to change the heading in response to a button-style
         * DPAD event.
         *
         * @param y the float y component of the joystick heading value
         */
        public void setHeadingY(float y) {
            mHeadingY = y;
            updateHeading();
        }

        /**
         * Sets the heading as floating point values returned by a joystick.
         * These values are normalized by the Android platform to be from -1.0
         * (left, top) to 1.0 (right, bottom)
         *
         * @param x the float x component of the joystick heading value
         * @param y the float y component of the joystick heading value
         */
        public void setHeading(float x, float y) {
            mHeadingX = x;
            mHeadingY = y;
            updateHeading();
        }

        /**
         * Converts the heading values from joystick devices to the polar
         * representation of the heading angle if the magnitude of the heading
         * is significant (> 0.1f).
         */
        private void updateHeading() {
            mHeadingMagnitude = pythag(mHeadingX, mHeadingY);
            if (mHeadingMagnitude > 0.1f) {
                mHeadingAngle = (float) Math.atan2(mHeadingY, mHeadingX);
            }
        }

        private float polarX() {
            return (float) Math.cos(mHeadingAngle);
        }

        private float polarY() {
            return (float) Math.sin(mHeadingAngle);
        }

        public void accelerate(String IP) {
            UdpClientThread.sendMessage("LA:" + String.format("%03d", polarX()) +
                    " LS:" + String.format("%03d", polarY()) + " ;", IP);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        getJoystickForId(deviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        RealJoystick joystick = getJoystickForId(deviceId);
        joystick.setInputDevice(InputDevice.getDevice(deviceId));
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        removeJoystickForID(deviceId);
    }

}
