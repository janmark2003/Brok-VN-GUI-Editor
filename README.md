# BrokVN GUI Scene Editor

<div align="center">
  <img src="logo.png" alt="BrokVN GUI Scene Editor Logo" width="520"/>
  <br/>
  <p><b>Visual Novel Studio, Clickable Hotspot Mapper, Character Sprite & Multi-Waypoint Walk Path Designer for the Brok VN Engine</b></p>

  [![Version](https://img.shields.io/badge/version-v2.0.0-00C3FF.svg?style=for-the-badge)](https://github.com/janmark2003/Brok-VN-GUI-Editor/releases)
  [![Platform](https://img.shields.io/badge/platform-Windows%20x64-blue.svg?style=for-the-badge&logo=windows)](https://github.com/janmark2003/Brok-VN-GUI-Editor/releases)
  [![Resolution](https://img.shields.io/badge/Native%20Resolution-1920%C3%971080-brightgreen.svg?style=for-the-badge)](https://github.com/janmark2003/Brok-VN-GUI-Editor)
  [![License](https://img.shields.io/badge/license-MIT-orange.svg?style=for-the-badge)](LICENSE)
</div>

---

## 🌟 Overview

**BrokVN GUI Scene Editor** is a visual IDE and coordinate-mapping studio designed specifically for authoring visual novel scenes and generating GameMaker-compatible `.txt` script code for the **Brok VN Engine** (*BROK the InvestiGator*).

It provides an interactive **1920×1080 native engine canvas** with real-time zooming, drag-and-drop sprite placement, spritesheet animation playback, multi-waypoint walking path trajectories, in-scene clickable text dialogues, layer visibility management, and instant script compilation.

---

## ✨ Key Features

### 🎯 1. Clickable Hotspots (`CLICKERNEW`)
- **Visual Hotspot Dragging**: Click and drag across the canvas to visually create bounding boxes (`X1, Y1, X2, Y2`) with live pixel dimensions and unbounded coordinates.
- **Image Model Clickers**: Route clickers directly to on-screen character sprites (`IMAGEMODEL=...`) with optional pixel-perfect alpha testing (`PRECISE=1`).
- **Text Model Clickers**: Attach clickable triggers directly to scene texts (`TEXTMODEL=...`) with custom hover highlight text and examine events.
- **Engine Attributes**: Full support for `HIGHLIGHT=1`, `HOTSPOT=1`, `HOTSPOTICON=`, `CANDPAD=1`, `TYPE=`, `STAYACTIVE=`, and `LAYER=`.

### 🖼️ 2. Character Sprite & Spritesheet Studio (`IMAGENEW`)
- **Drag & Drop Placement**: Drop single `.png` sprites or animated strip sheets directly onto the canvas.
- **Automatic Spritesheet Slicing**: Slice horizontal animation strips into frames with real-time playback loop (calibrated 8 FPS default), play/pause, step, and scrubber.
- **Anchor Origin Alignment**: 9 anchor origins (`CENTER`, `TOPLEFT`, `BOTTOMCENTER`, `BOTTOMLEFT`, `BOTTOMRIGHT`, etc.) with visual crosshairs.
- **Transformations & Depth**: Unbounded scale (`1%`–`∞%`), horizontal mirroring (`FLIPH=1`), and automated Y-ground depth sorting.

### 🚶 3. Multi-Waypoint Walk Path System (`IMAGEMOVE`)
- **Draggable Waypoint Pins**: Visually design walking paths with unlimited points (**Point A ➔ Point B ➔ Point C ➔ ...**).
- **Smooth Trajectory Simulation**: Real-time on-canvas character walking with directional flipping, speed configuration, and arrive event triggers (`ENDEVENT=`).

### 💬 4. In-Scene Text & Dialogue Engine (`TEXTNEW`)
- **Visual Text Placement**: Place text dialogues anywhere on the 1920×1080 canvas with unbounded coordinates.
- **Typography & Colors**: Select from engine fonts (`FONT_DEFAULT`, `FONT_TITLE`, `FONT_DIALOGUE`, `FONT_UI`, etc.), palette colors (`c_white`, `c_yellow`, `c_red`, etc.), and horizontal alignments (`CENTER`, `LEFT`, `RIGHT`).
- **Clickable Text Models**: Easily turn any placed text into an interactive clicker with hover glow effects and event execution.

### 🔍 5. Interactive Canvas Studio
- **Mouse Scroller Zooming**: Zoom in and out smoothly from $20\%$ up to $1000\%$ ($10\times$), dynamically anchored to your mouse cursor position.
- **Canvas Panning**: Middle-click or left-click background drag to pan across the canvas effortlessly.
- **Reset & Centering**: Double-click or press `Ctrl+0` to instantly reset zoom to 100% fit and center the view.

### 📑 6. Layer Manager & Live Visibility
- **Dedicated Layer Table**: View all background layers, sprites, and text objects in sorted draw order.
- **Instant Visibility Toggle**: Click visibility checkboxes in the inspector to show or hide sprites/texts without removing them from your project.

### 💾 7. Project Management & One-Click Exporter
- **Project Save & Load (`.brokproj`)**: Save full scene states and load existing projects anytime (`Ctrl+S`, `Ctrl+O`, `Ctrl+N`).
- **Overall BrokVN File Generator**: Compiles background definitions, character spritesheets, waypoint walk paths, dialogue texts, and clickable hotspots into a clean, copy-ready `.txt` script.

---

## ⌨️ Controls & Shortcuts

| Action | Control / Shortcut |
| :--- | :--- |
| **Draw Clicker Hotspot** | Left-Click & Drag (in Clicker mode) |
| **Move Waypoint Pin** | Left-Click & Drag Waypoint Pin (Point A, B, C...) |
| **Move Character Sprite** | Left-Click & Drag Blue Label Badge or Sprite Body |
| **Move Scene Text** | Left-Click & Drag Text on Canvas |
| **Zoom In / Out** | Mouse Wheel Scroll (or `Ctrl + +` / `Ctrl + -`) |
| **Pan Canvas** | Middle Mouse Drag / Scroll Click (or `Alt + Left-Click Drag`) |
| **Reset Zoom 100% & Center** | Double-Click on empty canvas (or `Ctrl + 0`) |
| **Full Screen Presentation** | `F11` |
| **Save Project** | `Ctrl + S` |
| **Open Project** | `Ctrl + O` |
| **New Project** | `Ctrl + N` |

---

## 📜 Brok VN Script Syntax Reference

### 1. Clicker Hotspot (`CLICKERNEW`)
```ini
CLICKERNEW=CLICK_INSPECT_TERMINAL
	X1=840
	Y1=420
	X2=1080
	Y2=650
	HIGHLIGHT=1
	TEXT=Examine Terminal
	CLICKEVENT=S01_TERMINAL_CLICKED
	HOTSPOT=1
	HOTSPOTICON=ICON_EXAMINE
	CANDPAD=1
	LAYER=1
```

### 2. Sprite Image Model Clicker (`IMAGEMODEL`)
```ini
CLICKERNEW=SELECTOR_ARROW
	IMAGEMODEL=NEXT_CHAPTER_CHOOSE
	PRECISE=1
	CLICKEVENT=S00_CHAPTER_NEXT
```

### 3. Clickable Scene Text (`TEXTNEW` + `TEXTMODEL`)
```ini
# Placed Text
EVENT=S01_SHOW_CHOICE_TEXT
	TEXTNEW=MY_CHOICE_ID
		TEXT=CRUCK CRUCK INNA MERST
		X=960
		Y=540
		FONT=FONT_TITLE
		COLOR1=c_yellow
		ALIGNH=CENTER
		DEPTH=1

# Interactive Clickable Text Model
CLICKERNEW=CLICK_MY_CHOICE
	TEXTMODEL=MY_CHOICE_ID
	HIGHLIGHT=1
	TEXT=Select Choice
	CLICKEVENT=S01_CHOICE_SELECTED
```

### 4. Character Animation & Multi-Waypoint Walking (`IMAGENEW` + `IMAGEMOVE`)
```ini
EVENT=S01_SCENE_START
	BACKGROUND=BG_MAIN_STREET
	FADEIN=MEDIUM

	# Character Spritesheet
	IMAGENEW=IMAGE_BROK
		FILE=SPR_BROK_WALK
		DEPTH=25
		ANIMSPEED=8
		NBFRAMES=6
		SCALE=100
		ORIGIN=CENTER
		X=250
		Y=560
		ANIMEND=REPEAT

	# Multi-Waypoint Walking Path (Point A -> Point B -> Point C)
	EVENT=S01_IMAGE_BROK_WALK_A_TO_B
		IMAGEMOVE=IMAGE_BROK
			MOVEX=960
			MOVEY=560
			SPEED=25
			ENDEVENT=S01_IMAGE_BROK_WALK_B_TO_C

	EVENT=S01_IMAGE_BROK_WALK_B_TO_C
		IMAGEMOVE=IMAGE_BROK
			MOVEX=1500
			MOVEY=560
			SPEED=25
			ENDEVENT=S01_IMAGE_BROK_ARRIVED
```

---

## 🚀 Installation & Running

### 📦 Standalone Windows Executable (No Java Required)
1. Download the latest release package: [`BrokVnGuiEditor-v2.0.0-windows-x64.zip`](https://github.com/janmark2003/Brok-VN-GUI-Editor/releases/latest).
2. Extract the ZIP archive anywhere on your PC.
3. Double-click **`BrokVnGuiEditor.exe`** to launch immediately.

### 🛠️ Running / Building from Source
If you wish to compile or develop from source:
```bash
# Clone the repository
git clone https://github.com/janmark2003/Brok-VN-GUI-Editor.git
cd Brok-VN-GUI-Editor

# Compile Java classes
javac -encoding UTF-8 BrokVnClickAreaWindow.java Main.java

# Launch software
javaw -cp . BrokVnClickAreaWindow
```

To package a standalone `.exe` using `jpackage`:
```cmd
build_exe.bat
```

---

## 👥 Credits & Acknowledgements

- **Janmark Abo**: Creator, Lead Developer, and UI/UX Designer of the **BrokVN GUI Scene Editor**.
- **COWCAT Games ([Fabrice Breton](https://www.cowcatgames.com))**: Creator of the acclaimed adventure game [**BROK the InvestiGator**](https://store.steampowered.com/app/949480/BROK_the_InvestiGator/) and developer of the underlying Brok Visual Novel Engine.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
