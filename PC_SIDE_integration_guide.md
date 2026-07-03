# Integrating the pairing gate into `tools/ai_scanner_tool.py`

The PC now **shows** a QR and **receives** the phone's IP. Five small edits.

---

## 1. Import + mix in (top of file, with other imports)

```python
from tools.scanner_pairing_gate import PairingGateMixin
```

Change the class declaration:

```python
# before
class AIScannerTool(BaseTool):
# after
class AIScannerTool(PairingGateMixin, BaseTool):
```

## 2. Initialise in `__init__` (after the wireless fields are set)

```python
        # PC-shows-QR pairing gate
        self._init_pairing_gate()
```

## 3. Add a "Pair with phone" button to the Wireless tab

In `_draw_wireless_tab(...)`, after the Connect/Reconnect button:

```python
        imgui.same_line()
        if imgui.button("Pair phone (QR)##wireless"):
            self.show_pair_window = True
            self._start_pairing_gate()
```

## 4. Poll the gate + draw the dialog in `draw_options`

Near the top of `draw_options` (once per frame), poll for a result so the
auto-connect happens on the UI thread:

```python
        # apply a phone address the gate may have received
        try:
            self._poll_pairing_result()
        except Exception:
            pass
```

Then, at the end of `draw_options`, alongside the other pop-out windows:

```python
            if getattr(self, "show_pair_window", False):
                self._draw_pair_window()
```

## 5. (optional) close the gate on tool shutdown / window close

Wherever you tear things down, you can call `self._stop_pairing_gate()`.
The gate also closes itself automatically the moment a phone pairs.

---

## Optional: nicer QR rendering

The gate renders the QR with the `qrcode` package if present, else falls back
to OpenCV's `QRCodeEncoder`. For the crispest result:

```
pip install qrcode
```

(OpenCV you already have, so it works without this.)

---

## End-to-end

1. PC: Scanner Pro -> Live Scanner Stream -> Wireless tab -> **Pair phone (QR)**.
   A QR appears encoding `{pc_ip, 8765, token}` and the PC starts listening.
2. Phone: plug in the USB-C webcam, open Scanner Bridge, tap **Scan PC Code**.
   The webcam reads the QR off your PC screen.
3. Phone: starts its MJPEG server and POSTs its own address to
   `http://<pc_ip>:8765/pair`.
4. PC: the gate validates the token, sets `camera_ip` to the phone's
   `<phone_ip>:8080/video`, and **auto-connects** the wireless stream.
5. Live feed flows phone -> PC.

Both devices must be on the **same Wi-Fi/LAN**. The token in the QR stops any
other device from hijacking the gate.
