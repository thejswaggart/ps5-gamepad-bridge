# ps5-gamepad-bridge — top-level build
#
#   make              build both android-server and ps5-client
#   make android      build the Android app only
#   make ps5          build the PS5 payload only
#   make clean        clean both
#   make clean-android
#   make clean-ps5
#   make deploy       build + deploy the PS5 payload to the PS5
#
# PS5_PAYLOAD_SDK defaults to the local SDK; override with e.g.
#   PS5_PAYLOAD_SDK=/other/path make ps5

ANDROID_DIR := android-server
PS5_DIR     := ps5-client
PS5_PAYLOAD_SDK ?= /home/aipc/projects/PS5_PayloadSDK
export PS5_PAYLOAD_SDK

.PHONY: all android ps5 clean clean-android clean-ps5 deploy

all: android ps5

android:
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

ps5:
	cd $(PS5_DIR) && $(MAKE)

clean: clean-android clean-ps5

clean-android:
	cd $(ANDROID_DIR) && ./gradlew clean

clean-ps5:
	cd $(PS5_DIR) && $(MAKE) clean

deploy: ps5
	cd $(PS5_DIR) && $(MAKE) deploy
