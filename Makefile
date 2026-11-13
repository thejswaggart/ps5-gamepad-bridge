# ps5-gamepad-bridge — top-level build
#
#   make              build both android-server and the payloads
#   make android      build the Android app (payloads are built first and
#                     bundled into the APK as assets)
#   make ps5          build the PS5 payload only
#   make ps4          build the PS4 payload only
#   make clean        clean both
#   make clean-android
#   make clean-ps5
#   make clean-ps4
#   make deploy       build + deploy the PS5 payload to the PS5
#   make deploy-ps4   build + deploy the PS4 payload to the PS4
#
# PS5_PAYLOAD_SDK / PS4_PAYLOAD_SDK default to the local SDKs; override
# with e.g. PS5_PAYLOAD_SDK=/other/path make ps5

ANDROID_DIR := android-server
PS5_DIR     := ps5-client
PS4_DIR     := ps4-client
PS5_PAYLOAD_SDK ?= /home/aipc/projects/ps5-payload-sdk
PS4_PAYLOAD_SDK ?= /home/aipc/projects/ps4-payload-sdk
export PS5_PAYLOAD_SDK PS4_PAYLOAD_SDK

.PHONY: all android ps5 ps4 clean clean-android clean-ps5 clean-ps4 deploy deploy-ps4

all: android

android: ps5 ps4
	@mkdir -p $(ANDROID_DIR)/app/src/main/assets
	cp -f $(PS5_DIR)/ghostpad-net.elf $(ANDROID_DIR)/app/src/main/assets/
	cp -f $(PS4_DIR)/ps4-ghostpad.elf $(ANDROID_DIR)/app/src/main/assets/
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

ps5:
	cd $(PS5_DIR) && $(MAKE)

ps4:
	cd $(PS4_DIR) && $(MAKE)

clean: clean-android clean-ps5 clean-ps4

clean-android:
	cd $(ANDROID_DIR) && ./gradlew clean

clean-ps5:
	cd $(PS5_DIR) && $(MAKE) clean

clean-ps4:
	cd $(PS4_DIR) && $(MAKE) clean

deploy: ps5
	cd $(PS5_DIR) && $(MAKE) deploy

deploy-ps4: ps4
	cd $(PS4_DIR) && $(MAKE) deploy
