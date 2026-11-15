# ghostpad client payload — PS4 + PS5, one source tree
#
#   make            build both payloads
#   make ps5        build the PS5 payload only  -> ps5-client.elf
#   make ps4        build the PS4 payload only  -> ps4-client.elf
#   make clean      remove both payloads
#   make deploy           deploy the PS5 payload (nc-style)
#   make deploy-ps4      deploy the PS4 payload
#
# PS5_PAYLOAD_SDK / PS4_PAYLOAD_SDK default to the local SDKs; override
# with e.g. PS5_PAYLOAD_SDK=/other/path make ps5

PS5_HOST  ?= PS5
PS4_HOST  ?= PS4
PS5_PORT  ?= 9021
PS4_PORT  ?= 9090

PS5_PAYLOAD_SDK ?= /home/aipc/projects/ps5-payload-sdk
PS4_PAYLOAD_SDK ?= /home/aipc/projects/ps4-payload-sdk
export PS5_PAYLOAD_SDK PS4_PAYLOAD_SDK

.PHONY: all ps5 ps4 clean deploy deploy-ps4

all: ps5 ps4

ps5:
	$(MAKE) PLATFORM=ps5 ps5-client.elf

ps4:
	$(MAKE) PLATFORM=ps4 ps4-client.elf

clean:
	rm -f ps5-client.elf ps4-client.elf

deploy: ps5
	nc -w 8 $(PS5_HOST) $(PS5_PORT) < ps5-client.elf

deploy-ps4: ps4
	nc -w 8 $(PS4_HOST) $(PS4_PORT) < ps4-client.elf

# ── per-platform build (run via the ps5 / ps4 targets above) ──────────
# The platform is selected by the -D define: __PROSPERO__ (PS5) /
# __ORBIS__ (PS4); gc_main.c & shellui_pad.c switch on it via #ifdef.
ifeq ($(PLATFORM),ps5)
include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
CFLAGS  += -D__PROSPERO__ -Wall -Wextra -g -O2 -fPIC -fno-stack-protector
LDFLAGS += -lScePad -lSceUserService -lpthread -ldl
else ifeq ($(PLATFORM),ps4)
include $(PS4_PAYLOAD_SDK)/toolchain/orbis.mk
CFLAGS  += -D__ORBIS__ -Wall -Wextra -g -O2 -fPIC -fno-stack-protector
LDFLAGS += -lScePad -lSceUserService -lpthread -ldl
else
$(if $(PLATFORM),$(error PLATFORM must be ps5 or ps4))
endif

ps5-client.elf: gc_main.c shellui_pad.c rumble_out.c gc_types.h padframe.h shellui_pad.h rumble_out.h
	$(CC) $(CFLAGS) -o $@ gc_main.c shellui_pad.c rumble_out.c $(LDFLAGS)

ps4-client.elf: gc_main.c shellui_pad.c rumble_out.c gc_types.h padframe.h shellui_pad.h rumble_out.h
	$(CC) $(CFLAGS) -o $@ gc_main.c shellui_pad.c rumble_out.c $(LDFLAGS)
