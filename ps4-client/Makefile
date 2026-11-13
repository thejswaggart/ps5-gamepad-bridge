PS4_HOST ?= PS4
PORT     ?= 9021
TARGET   := ps4-ghostpad.elf

ifndef PS4_PAYLOAD_SDK
$(error PS4_PAYLOAD_SDK is not set)
endif

include $(PS4_PAYLOAD_SDK)/toolchain/orbis.mk

CFLAGS  += -D__ORBIS__ -Wall -Wextra -g -O2 -fPIC -fno-stack-protector
LDFLAGS += -lScePad -lSceUserService -lpthread -ldl

SRC := gc_main.c shellui_pad.c rumble_out.c

.PHONY: all clean deploy

all: $(TARGET)

$(TARGET): $(SRC)
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)

clean:
	rm -f $(TARGET)

deploy: $(TARGET)
	nc -w 8 $(PS4_HOST) $(PORT) < $(TARGET)
