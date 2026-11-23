#!/usr/bin/env python3
"""
Irbis Remote — пульт для интерактивной панели Irbis с iPad по Wi‑Fi (без ИК).

iPad открывает веб-страницу в Safari, команды идут по сети на этот сервер,
а сервер передаёт их панели одним из двух способов:

  --mode adb    Встроенный Android панели. Сервер (ноутбук/ПК/Raspberry Pi
                в той же сети) управляет панелью через ADB по сети
                (adb connect <ip>:5555). Нужен установленный adb.

  --mode local  Сервер запущен прямо на OPS‑модуле (Windows/Linux‑компьютер,
                вставленный в панель). Клавиатура и мышь эмулируются через
                pyautogui.

Только стандартная библиотека Python 3.8+ (+ pyautogui для режима local).
"""

import argparse
import json
import os
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")


# --------------------------------------------------------------------------
# Бэкенд 1: Android-панель через ADB
# --------------------------------------------------------------------------
ANDROID_KEYS = {
    "up": 19, "down": 20, "left": 21, "right": 22, "ok": 23,
    "back": 4, "home": 3, "menu": 82, "apps": 187,
    "vol_up": 24, "vol_down": 25, "mute": 164,
    "power": 26, "sleep": 223, "wakeup": 224,
    "page_up": 92, "page_down": 93,
    "play_pause": 85, "enter": 66, "backspace": 67,
    "settings": 176, "input": 178,
}


class AdbBackend:
    name = "adb"
    pad_mode = "absolute"  # тачпад = уменьшенная копия экрана панели

    def __init__(self, target):
        if not shutil.which("adb"):
            sys.exit("Не найден adb. Установите Android Platform Tools и добавьте adb в PATH.")
        self.target = target if ":" in target else f"{target}:5555"
        self.lock = threading.Lock()
        self.shell = None
        self.width, self.height = 1920, 1080
        self._connect()

    def _run(self, *args, timeout=10):
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)

    def _connect(self):
        out = self._run("connect", self.target)
        print(f"[adb] {out.stdout.strip() or out.stderr.strip()}")
        size = self._run("-s", self.target, "shell", "wm", "size")
        # "Physical size: 3840x2160" (+ возможно "Override size: ...") — берём последнюю
        for line in size.stdout.splitlines():
            if "size:" in line:
                try:
                    w, h = line.split(":")[1].strip().split("x")
                    self.width, self.height = int(w), int(h)
                except ValueError:
                    pass
        print(f"[adb] Разрешение экрана: {self.width}x{self.height}")
        self._open_shell()

    def _open_shell(self):
        # Постоянный shell: команды отправляются без запуска нового процесса adb
        # на каждое нажатие — так заметно меньше задержка.
        self.shell = subprocess.Popen(
            ["adb", "-s", self.target, "shell"],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            text=True, bufsize=1,
        )

    def _exec(self, cmd):
        with self.lock:
            for attempt in range(2):
                try:
                    if self.shell is None or self.shell.poll() is not None:
                        self._connect()
                    self.shell.stdin.write(cmd + "\n")
                    self.shell.stdin.flush()
                    return
                except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                    self.shell = None
                    if attempt:
                        raise RuntimeError("Нет связи с панелью по ADB")

    def _xy(self, nx, ny):
        nx, ny = min(max(float(nx), 0.0), 1.0), min(max(float(ny), 0.0), 1.0)
        return int(nx * (self.width - 1)), int(ny * (self.height - 1))

    # --- API ---
    def key(self, name):
        code = ANDROID_KEYS.get(name)
        if code is None:
            raise ValueError(f"Неизвестная клавиша: {name}")
        self._exec(f"input keyevent {code}")

    def tap(self, x, y):
        self._exec("input tap %d %d" % self._xy(x, y))

    def swipe(self, x1, y1, x2, y2, ms=200):
        a, b = self._xy(x1, y1), self._xy(x2, y2)
        self._exec(f"input swipe {a[0]} {a[1]} {b[0]} {b[1]} {int(ms)}")

    def long_press(self, x, y):
        a = self._xy(x, y)
        self._exec(f"input swipe {a[0]} {a[1]} {a[0]} {a[1]} 800")

    def scroll(self, dy):
        # Прокрутка = свайп по центру экрана
        d = max(min(float(dy), 0.4), -0.4)
        self.swipe(0.5, 0.5, 0.5, 0.5 - d, 150)

    def move(self, dx, dy):
        raise ValueError("В режиме adb относительное перемещение мыши не поддерживается")

    def click(self, button="left"):
        raise ValueError("В режиме adb используйте tap")

    def text(self, s):
        # Стандартная команда `input text` Android вводит только ASCII.
        if not s.isascii():
            raise ValueError("Через ADB можно вводить только латиницу/цифры (ограничение Android `input text`)")
        s = s.replace(" ", "%s")
        self._exec("input text " + shlex.quote(s))


# --------------------------------------------------------------------------
# Бэкенд 2: OPS-компьютер (Windows/Linux) через pyautogui
# --------------------------------------------------------------------------
LOCAL_KEYS = {
    "up": ["up"], "down": ["down"], "left": ["left"], "right": ["right"],
    "ok": ["enter"], "enter": ["enter"], "back": ["esc"], "backspace": ["backspace"],
    "home": ["win"], "menu": ["apps"], "apps": ["alt", "tab"],
    "vol_up": ["volumeup"], "vol_down": ["volumedown"], "mute": ["volumemute"],
    "page_up": ["pageup"], "page_down": ["pagedown"], "play_pause": ["playpause"],
    "f5": ["f5"], "shift_f5": ["shift", "f5"], "black": ["b"],
    "desktop": ["win", "d"], "close": ["alt", "f4"],
}


class LocalBackend:
    name = "local"
    pad_mode = "relative"  # тачпад как у ноутбука

    def __init__(self):
        try:
            import pyautogui
        except ImportError:
            sys.exit("Для режима local установите pyautogui: pip install pyautogui")
        pyautogui.FAILSAFE = False
        pyautogui.PAUSE = 0
        self.g = pyautogui
        self.lock = threading.Lock()
        self.down = False

    def key(self, name):
        keys = LOCAL_KEYS.get(name)
        if keys is None:
            raise ValueError(f"Неизвестная клавиша: {name}")
        with self.lock:
            self.g.hotkey(*keys) if len(keys) > 1 else self.g.press(keys[0])

    def move(self, dx, dy):
        with self.lock:
            self.g.moveRel(int(dx), int(dy), _pause=False)

    def click(self, button="left"):
        if button not in ("left", "right", "middle"):
            raise ValueError("button: left|right|middle")
        with self.lock:
            self.g.click(button=button)

    def drag(self, state):
        with self.lock:
            if state == "down" and not self.down:
                self.g.mouseDown(); self.down = True
            elif state == "up" and self.down:
                self.g.mouseUp(); self.down = False

    def scroll(self, dy):
        with self.lock:
            self.g.scroll(int(-float(dy) * 10))

    def text(self, s):
        # pyautogui.write печатает только символы, которые есть на текущей
        # раскладке; для кириллицы используем буфер обмена, если доступен.
        with self.lock:
            if s.isascii():
                self.g.write(s, interval=0.01)
                return
            try:
                import pyperclip
            except ImportError:
                raise ValueError("Для ввода кириллицы установите pyperclip: pip install pyperclip")
            pyperclip.copy(s)
            self.g.hotkey("ctrl", "v")

    def tap(self, x, y):
        w, h = self.g.size()
        with self.lock:
            self.g.click(int(float(x) * (w - 1)), int(float(y) * (h - 1)))

    def swipe(self, *a, **k):
        raise ValueError("В режиме local используйте move/drag")

    def long_press(self, x, y):
        self.click("right")


# --------------------------------------------------------------------------
# HTTP-сервер
# --------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    backend = None
    pin = None
    server_version = "IrbisRemote/1.0"

    def log_message(self, fmt, *args):
        if os.environ.get("IRBIS_DEBUG"):
            super().log_message(fmt, *args)

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        if not self.pin:
            return True
        q = parse_qs(urlparse(self.path).query)
        given = self.headers.get("X-Pin") or (q.get("pin") or [""])[0]
        return secrets.compare_digest(given, self.pin)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/info":
            if not self._authorized():
                return self._send(401, {"error": "Неверный PIN"})
            b = self.backend
            return self._send(200, {"mode": b.name, "pad": b.pad_mode,
                                    "keys": sorted((ANDROID_KEYS if b.name == "adb" else LOCAL_KEYS).keys())})
        files = {"/": ("index.html", "text/html; charset=utf-8"),
                 "/manifest.json": ("manifest.json", "application/manifest+json"),
                 "/icon.svg": ("icon.svg", "image/svg+xml")}
        if path in files:
            name, ctype = files[path]
            with open(os.path.join(STATIC_DIR, name), "rb") as f:
                return self._send(200, f.read(), ctype)
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._authorized():
            return self._send(401, {"error": "Неверный PIN"})
        path = urlparse(self.path).path
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length > 10_000:
                return self._send(413, {"error": "too large"})
            p = json.loads(self.rfile.read(length) or b"{}")
            b = self.backend
            actions = {
                "/api/key": lambda: b.key(p["key"]),
                "/api/tap": lambda: b.tap(p["x"], p["y"]),
                "/api/swipe": lambda: b.swipe(p["x1"], p["y1"], p["x2"], p["y2"], p.get("ms", 200)),
                "/api/long": lambda: b.long_press(p["x"], p["y"]),
                "/api/move": lambda: b.move(p["dx"], p["dy"]),
                "/api/click": lambda: b.click(p.get("button", "left")),
                "/api/drag": lambda: b.drag(p["state"]),
                "/api/scroll": lambda: b.scroll(p["dy"]),
                "/api/text": lambda: b.text(str(p["text"])[:500]),
            }
            if path not in actions:
                return self._send(404, {"error": "not found"})
            actions[path]()
            self._send(200, {"ok": True})
        except (KeyError, ValueError, TypeError, AttributeError) as e:
            self._send(400, {"error": str(e) or "bad request"})
        except RuntimeError as e:
            self._send(503, {"error": str(e)})


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips) or ["<IP-этого-компьютера>"]


def main():
    ap = argparse.ArgumentParser(description="Пульт для панели Irbis с iPad по Wi-Fi")
    ap.add_argument("--mode", choices=["adb", "local"], required=True,
                    help="adb — Android панели по сети; local — сервер запущен на OPS-компьютере панели")
    ap.add_argument("--board", help="IP панели для режима adb, напр. 192.168.1.50 (порт 5555 по умолчанию)")
    ap.add_argument("--port", type=int, default=8080, help="порт веб-пульта (по умолчанию 8080)")
    ap.add_argument("--pin", help="PIN для доступа к пульту (рекомендуется в школьной/офисной сети)")
    a = ap.parse_args()

    if a.mode == "adb":
        if not a.board:
            ap.error("для --mode adb укажите --board <IP панели>")
        Handler.backend = AdbBackend(a.board)
    else:
        Handler.backend = LocalBackend()
    Handler.pin = a.pin

    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    print("\nПульт запущен. Откройте на iPad в Safari:")
    for ip in local_ips():
        print(f"   http://{ip}:{a.port}/" + (f"?pin={a.pin}" if a.pin else ""))
    print("Совет: «Поделиться» → «На экран Домой» — пульт откроется как приложение.\nCtrl+C — остановить.\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
