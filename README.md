android-open-accessory-bridge
=============================

A USB communication bridge using Android Open Accessory Protocol. Allows sending of messages between a Python script running on a PC and Android activity running on an Android device.

Includes a simple "ping-pong" test for Nexus 4. Just install the Android application and then run the Python script. When the script starts the application will launch and they'll send tiny incremental messages back and forth. When you stop the Python script the Android application closes - it's that easy!

Python script requires pyusb at http://sourceforge.net/apps/trac/pyusb/

My goal in writing this was to make my Nexus 4 into a sensor for a Raspberry Pi. Raspberry Pi doesn't have ADB and Nexus 4 can't easily use USB-OTG so AOAP is the only way to communicate.
