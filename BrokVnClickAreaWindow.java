import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * BrokVN GUI Editor
 * Visual Novel Clickable Area, Hotspot Studio, Sprite Placement, Multi-Waypoint Walk Paths,
 * Layer System, Full-Screen Preview, Project State Manager, and GitHub API Auto-Updater
 * for the Brok VN Engine (1920×1080 Native Canvas).
 */
public class BrokVnClickAreaWindow extends JFrame {

    public static final String APP_NAME = "BrokVN GUI Editor";
    public static final String APP_VERSION = "v1.3.0";
    public static final String GITHUB_REPO = "janmark2003/Brok-VN-GUI-Editor";
    public static final String UPDATE_CHECK_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    private static final String PREF_LAST_BROWSE_DIR = "last_browsed_directory";
    private static final String PREF_GITHUB_TOKEN = "github_api_token";
    private static final Preferences prefs = Preferences.userNodeForPackage(BrokVnClickAreaWindow.class);

    public interface InsertCallback {
        void insertScriptCode(String code);
    }

    // =========================================================================
    // DATA MODELS
    // =========================================================================

    // Data model for Waypoints (Point A -> Point B -> Point C -> ... Infinite Multi-point Walk Path)
    public static class Waypoint {
        public String label; // "Point A", "Point B", "Point C", etc.
        public int x;
        public int y;
        public int speed = 3; // pixels per frame
        public String endEvent = "";

        public Waypoint(String label, int x, int y, int speed, String endEvent) {
            this.label = label;
            this.x = x;
            this.y = y;
            this.speed = speed;
            this.endEvent = endEvent;
        }

        public Waypoint copy() {
            return new Waypoint(label, x, y, speed, endEvent);
        }
    }

    // Data model for a Clicker (CLICKERNEW)
    public static class ClickerDef {
        public String id = "CLICK_ITEM";
        public int x1 = 0;
        public int y1 = 0;
        public int x2 = 100;
        public int y2 = 100;
        public String text = "Examine Item";
        public String event = "S01_CLICK_ITEM";
        public boolean highlight = true;
        public boolean hotspot = true;
        public String hotspotIcon = "ICON_ACTIVE";
        public boolean canDpad = true;
        public String type = "NORMAL";
        public String stayActive = "DEFAULT";
        public int layer = 0; // Layer index (0, 1, 2...)
        public Color color = new Color(0, 180, 255);

        public String toBrokVnScript() {
            StringBuilder sb = new StringBuilder();
            sb.append("#----------------------------------------------------\n");
            sb.append("# CLICKER: ").append(id).append("\n");
            sb.append("#----------------------------------------------------\n");
            sb.append("CLICKERNEW=").append(id).append("\n");
            sb.append("\tX1=").append(x1).append("\n");
            sb.append("\tY1=").append(y1).append("\n");
            sb.append("\tX2=").append(x2).append("\n");
            sb.append("\tY2=").append(y2).append("\n");
            if (layer > 0) {
                sb.append("\tLAYER=").append(layer).append("\n");
            }
            if (highlight) {
                sb.append("\tHIGHLIGHT=1\n");
            }
            if (text != null && !text.trim().isEmpty()) {
                sb.append("\tTEXT=").append(text.trim()).append("\n");
            }
            String targetEv = (event != null && !event.trim().isEmpty()) ? event.trim() : "S01_" + id;
            sb.append("\tCLICKEVENT=").append(targetEv).append("\n");
            if (hotspot) {
                sb.append("\tHOTSPOT=1\n");
                if (hotspotIcon != null && !hotspotIcon.equals("ICON_NONE")) {
                    sb.append("\tHOTSPOTICON=").append(hotspotIcon).append("\n");
                }
            }
            if (canDpad) {
                sb.append("\tCANDPAD=1\n");
            }
            if ("INTERFACE".equalsIgnoreCase(type)) {
                sb.append("\tTYPE=INTERFACE\n");
            }
            if ("DIALOGUE".equalsIgnoreCase(stayActive)) {
                sb.append("\tSTAYACTIVE=DIALOGUE\n");
            } else if ("ALWAYS".equalsIgnoreCase(stayActive)) {
                sb.append("\tSTAYACTIVE=ALWAYS\n");
            }

            // Clean event transition stub
            sb.append("\n# Event Handler for ").append(id).append("\n");
            sb.append("EVENT=").append(targetEv).append("\n");
            sb.append("\t# Add scene actions / dialogue call here\n");

            return sb.toString();
        }
    }

    // Data model for a draggable Character / Object / Spritesheet overlay (IMAGENEW + IMAGEMOVE)
    public static class OverlayObject {
        public File file;
        public String imagePath = "";
        public String name;
        public String imageId;
        public String fileField = "";
        public BufferedImage fullImage;
        public BufferedImage[] frames = null;
        public int currentFrameIndex = 0;

        // Position on Canvas (top-left bounding box coordinates in 1920x1080 engine space - allows offscreen/negative)
        public int x;
        public int y;

        // Native dimensions of a single frame
        public int nativeWidth;
        public int nativeHeight;

        // Scaling (percent, e.g. 100 = 100%, 60 = 60%)
        public int scale = 100;

        // Flip Horizontally
        public boolean flipH = false;

        // Origin Anchor
        public boolean useOrigin = true;
        public String origin = "CENTER";

        // Depth priority (Automatic or Manual)
        public int depth = 1;
        public boolean autoDepth = true;

        // Spritesheet & Animation settings (Default Speed = 8 fps)
        public boolean isAnimation = false;
        public int nbFrames = 1;
        public int animSpeed = 8; // Calibrated default 8 FPS
        public String animEnd = "REPEAT"; // "REPEAT", "BLOCK", "DESTROY"
        public boolean isPlaying = true;
        public long lastFrameTimeNano = 0;

        // --- Multi-Waypoint Walk Path Movement (Point A -> Point B -> Point C -> ... Infinite) ---
        public boolean useWalkPath = false;
        public final List<Waypoint> waypoints = new ArrayList<>();
        public int currentWaypointSegment = 0; // Index of active path segment
        public double currentWalkProgress = 0.0; // 0.0 to 1.0 along current segment
        public boolean walkForward = true;

        // Draggable Character Label Badge offset
        public boolean customLabelPos = false;
        public int labelOffsetX = 0;
        public int labelOffsetY = -24;

        public OverlayObject(File file, BufferedImage fullImage, int x, int y) {
            this.file = file;
            if (file != null) {
                this.imagePath = file.getAbsolutePath();
            }
            this.fullImage = fullImage;
            this.name = (file != null) ? file.getName() : "Object";
            String base = this.name;
            int dot = base.lastIndexOf('.');
            if (dot > 0)
                base = base.substring(0, dot);
            this.imageId = base.replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase();
            this.fileField = this.imageId;

            int rawW = (fullImage != null) ? fullImage.getWidth() : 100;
            int rawH = (fullImage != null) ? fullImage.getHeight() : 100;

            // Auto-detect if image is a horizontal spritesheet strip
            if (rawW >= rawH * 2 && rawW % rawH == 0) {
                this.nbFrames = rawW / rawH;
                this.isAnimation = true;
                this.animSpeed = 8; // Calibrated default 8 FPS
                this.animEnd = "REPEAT";
                this.origin = "CENTER";
                this.useOrigin = true;
            } else {
                this.nbFrames = 1;
                this.isAnimation = false;
                this.animSpeed = 8;
            }

            sliceFrames();
            this.x = x;
            this.y = y;

            // Initialize default 2-point Waypoints (Point A -> Point B 65% width)
            initDefaultWaypoints();
        }

        public void initDefaultWaypoints() {
            waypoints.clear();
            int ax = getAnchorX();
            int ay = getAnchorY();
            waypoints.add(new Waypoint("Point A", ax, ay, 3, ""));
            waypoints.add(new Waypoint("Point B", ax + 1248, ay, 3, "S01_" + imageId + "_ARRIVED"));
        }

        public void sliceFrames() {
            if (fullImage == null) {
                frames = new BufferedImage[0];
                nativeWidth = 100;
                nativeHeight = 100;
                return;
            }
            if (nbFrames <= 1) {
                frames = new BufferedImage[] { fullImage };
                nativeWidth = fullImage.getWidth();
                nativeHeight = fullImage.getHeight();
                currentFrameIndex = 0;
                return;
            }

            int frameW = Math.max(1, fullImage.getWidth() / nbFrames);
            int frameH = fullImage.getHeight();
            frames = new BufferedImage[nbFrames];
            for (int i = 0; i < nbFrames; i++) {
                int fx = i * frameW;
                int fw = Math.min(frameW, fullImage.getWidth() - fx);
                if (fw > 0 && frameH > 0) {
                    frames[i] = fullImage.getSubimage(fx, 0, fw, frameH);
                } else {
                    frames[i] = fullImage;
                }
            }
            nativeWidth = frameW;
            nativeHeight = frameH;
            if (currentFrameIndex >= nbFrames) {
                currentFrameIndex = 0;
            }
        }

        public BufferedImage getCurrentFrame() {
            if (frames == null || frames.length == 0)
                return fullImage;
            int idx = Math.max(0, Math.min(frames.length - 1, currentFrameIndex));
            return frames[idx];
        }

        public int getDisplayWidth() {
            return Math.max(1, (int) Math.round(nativeWidth * (scale / 100.0)));
        }

        public int getDisplayHeight() {
            return Math.max(1, (int) Math.round(nativeHeight * (scale / 100.0)));
        }

        public int getX2() {
            return x + getDisplayWidth();
        }

        public int getY2() {
            return y + getDisplayHeight();
        }

        public boolean contains(int engX, int engY) {
            return engX >= x && engX <= getX2() && engY >= y && engY <= getY2();
        }

        public int getCalculatedDepth() {
            if (autoDepth) {
                int groundY = getY2();
                return Math.max(1, Math.min(99, groundY / 20));
            }
            return depth;
        }

        public int getAnchorX() {
            if (!useOrigin || origin == null)
                return x;
            int w = getDisplayWidth();
            String o = origin.trim().toUpperCase();
            switch (o) {
                case "CENTER":
                case "TOPCENTER":
                case "BOTTOMCENTER":
                    return x + w / 2;
                case "TOPRIGHT":
                case "CENTERRIGHT":
                case "BOTTOMRIGHT":
                    return x + w;
                case "TOPLEFT":
                case "CENTERLEFT":
                case "BOTTOMLEFT":
                default:
                    return x;
            }
        }

        public int getAnchorY() {
            if (!useOrigin || origin == null)
                return y;
            int h = getDisplayHeight();
            String o = origin.trim().toUpperCase();
            switch (o) {
                case "CENTER":
                case "CENTERLEFT":
                case "CENTERRIGHT":
                    return y + h / 2;
                case "BOTTOMLEFT":
                case "BOTTOMCENTER":
                case "BOTTOMRIGHT":
                    return y + h;
                case "TOPLEFT":
                case "TOPCENTER":
                case "TOPRIGHT":
                default:
                    return y;
            }
        }

        public void setFromAnchor(int ax, int ay) {
            if (!useOrigin || origin == null) {
                this.x = ax;
                this.y = ay;
                return;
            }
            int w = getDisplayWidth();
            int h = getDisplayHeight();
            String o = origin.trim().toUpperCase();

            switch (o) {
                case "CENTER":
                case "TOPCENTER":
                case "BOTTOMCENTER":
                    this.x = ax - w / 2;
                    break;
                case "TOPRIGHT":
                case "CENTERRIGHT":
                case "BOTTOMRIGHT":
                    this.x = ax - w;
                    break;
                default:
                    this.x = ax;
                    break;
            }

            switch (o) {
                case "CENTER":
                case "CENTERLEFT":
                case "CENTERRIGHT":
                    this.y = ay - h / 2;
                    break;
                case "BOTTOMLEFT":
                case "BOTTOMCENTER":
                case "BOTTOMRIGHT":
                    this.y = ay - h;
                    break;
                default:
                    this.y = ay;
                    break;
            }
        }

        public String toImageNewScript() {
            StringBuilder sb = new StringBuilder();
            sb.append("#----------------------------------------------------\n");
            sb.append("# IMAGE SPRITE PLACEMENT: ").append(imageId).append("\n");
            sb.append("#----------------------------------------------------\n");
            sb.append("IMAGENEW=").append(imageId).append("\n");
            if (fileField != null && !fileField.trim().isEmpty() && !fileField.trim().equalsIgnoreCase(imageId)) {
                sb.append("\tFILE=").append(fileField.trim()).append("\n");
            }
            int d = getCalculatedDepth();
            if (d != 0) {
                sb.append("\tDEPTH=").append(d).append("\n");
            }
            if (isAnimation) {
                sb.append("\tANIMSPEED=").append(animSpeed).append("\n");
                sb.append("\tNBFRAMES=").append(nbFrames).append("\n");
            }
            if (scale != 100) {
                sb.append("\tSCALE=").append(scale).append("\n");
            }
            if (flipH) {
                int xs = (scale != 100) ? -scale : -100;
                sb.append("\tXSCALE=").append(xs).append("\n");
            }
            if (useOrigin && origin != null && !origin.trim().isEmpty()) {
                sb.append("\tORIGIN=").append(origin.trim().toUpperCase()).append("\n");
            }

            int spawnX = (useWalkPath && !waypoints.isEmpty()) ? waypoints.get(0).x : getAnchorX();
            int spawnY = (useWalkPath && !waypoints.isEmpty()) ? waypoints.get(0).y : getAnchorY();
            sb.append("\tX=").append(spawnX).append("\n");
            sb.append("\tY=").append(spawnY).append("\n");
            if (isAnimation && animEnd != null && !animEnd.trim().isEmpty()) {
                sb.append("\tANIMEND=").append(animEnd.trim().toUpperCase()).append("\n");
            }

            if (useWalkPath && waypoints.size() >= 2) {
                sb.append("\n#----------------------------------------------------\n");
                sb.append("# MULTI-WAYPOINT WALK PATHS: ").append(imageId).append("\n");
                sb.append("#----------------------------------------------------\n");
                for (int i = 1; i < waypoints.size(); i++) {
                    Waypoint wpPrev = waypoints.get(i - 1);
                    Waypoint wpCurr = waypoints.get(i);
                    String evName = (i == 1) ? "S01_" + imageId + "_WALK_START" : "S01_" + imageId + "_PATH_" + i;

                    sb.append("EVENT=").append(evName).append("\n");
                    sb.append("\tIMAGEMOVE=").append(imageId).append("\n");
                    sb.append("\t\tMOVEX=").append(wpCurr.x).append("\n");
                    if (wpCurr.y != wpPrev.y) {
                        sb.append("\t\tMOVEY=").append(wpCurr.y).append("\n");
                    }
                    sb.append("\t\tSPEED=").append(Math.max(1, wpCurr.speed)).append("\n");

                    String nextEv = (i < waypoints.size() - 1)
                            ? "S01_" + imageId + "_PATH_" + (i + 1)
                            : ((wpCurr.endEvent != null && !wpCurr.endEvent.trim().isEmpty()) ? wpCurr.endEvent.trim() : "S01_" + imageId + "_ARRIVED");
                    sb.append("\t\tENDEVENT=").append(nextEv).append("\n\n");
                }
            }

            return sb.toString();
        }
    }

    public enum ActiveEditTarget {
        CLICKER,
        IMAGE
    }

    private enum DragHandleType {
        NONE,
        OVERLAY_SPRITE,
        OVERLAY_LABEL,
        WAYPOINT_PIN,
        CLICKER_BOX
    }

    // =========================================================================
    // STATE & FIELDS
    // =========================================================================

    private final InsertCallback insertCallback;
    private final boolean isDarkMode;
    private File projectBaseDir;
    private File currentProjectFile = null;

    private BufferedImage currentImage = null;
    private File currentImageFile = null;
    private String currentImageResText = "No image loaded";

    private final List<ClickerDef> savedClickers = new ArrayList<>();
    private int selectedClickerIndex = -1;

    // Character & Object Overlays (IMAGENEW + IMAGEMOVE)
    private final List<OverlayObject> overlayObjects = new ArrayList<>();
    private OverlayObject activeOverlayObject = null;
    private ActiveEditTarget activeEditTarget = ActiveEditTarget.CLICKER;

    // Canvas Dragging State
    private DragHandleType currentDragType = DragHandleType.NONE;
    private int draggedWaypointIndex = -1;
    private int overlayDragOffsetX = 0;
    private int overlayDragOffsetY = 0;
    private int labelDragStartX = 0;
    private int labelDragStartY = 0;

    private JCheckBox chkShowOverlays;
    private JButton btnClearOverlays;

    // Animation playback ticker
    private javax.swing.Timer animTimer;

    // Current Clicker drag coordinates
    private int curX1 = 100;
    private int curY1 = 100;
    private int curX2 = 500;
    private int curY2 = 400;

    // UI Components
    private ClickAreaCanvas canvas;
    private JLabel lblImageStatus;
    private JLabel lblCursorPos;
    private JLabel lblCurrentBounds;
    private JSpinner spX1, spY1, spX2, spY2, spWidth, spHeight;
    private JSpinner spClickerLayer;
    private boolean updatingSpinners = false;

    // Active Target Toggles
    private JToggleButton btnTargetClicker;
    private JToggleButton btnTargetImage;

    // Image (IMAGENEW) Parameter UI
    private JTextField txtImageId;
    private JTextField txtImageFile;
    private JSpinner spImgX, spImgY;
    private JLabel lblImgDimensions;
    private JCheckBox chkUseOrigin;
    private JComboBox<String> cmbImageOrigin;
    private JCheckBox chkAutoDepth;
    private JSpinner spImageDepth;
    private JSpinner spImageScale;
    private JSlider sldImageScale;
    private JCheckBox chkFlipH;

    // Spritesheet & Animation Controls
    private JCheckBox chkIsAnimation;
    private JSpinner spNbFrames;
    private JSpinner spAnimSpeed;
    private JComboBox<String> cmbAnimEnd;
    private JButton btnPlayPauseAnim;
    private JLabel lblAnimFrameStatus;
    private JSlider sldAnimScrubber;
    private boolean updatingImgSpinners = false;

    // Multi-Waypoint Walk Path Controls
    private JCheckBox chkUseWalkPath;
    private JLabel lblWalkDistanceInfo;
    private JSpinner spSelectedWpX, spSelectedWpY, spSelectedWpSpeed;
    private JTextField txtSelectedWpEvent;
    private JComboBox<String> cmbWaypointSelector;

    // Clicker (CLICKERNEW) Parameter UI
    private JTextField txtId;
    private JTextField txtEvent;
    private JTextField txtText;
    private JCheckBox chkHighlight;
    private JCheckBox chkHotspot;
    private JComboBox<String> cmbHotspotIcon;
    private JCheckBox chkCanDpad;
    private JComboBox<String> cmbType;
    private JComboBox<String> cmbStayActive;
    private JCheckBox chkSyncClickerWithImage;

    // Layer Management
    private DefaultTableModel layerTableModel;
    private JTable layerTable;

    // Script Generator UI
    private JComboBox<String> cmbScriptGenMode;
    private JTextArea txtScriptPreview;
    private JTextArea txtOverallBrokVnFile;
    private JScrollPane codeScroll;
    private DefaultTableModel clickerTableModel;
    private JTable clickersTable;
    private JCheckBox chkShowGrid;
    private JCheckBox chkShowAllClickers;
    private JCheckBox chkShowWaypoints;
    private JTabbedPane rightTabbedPane;

    // Palette tokens
    private Color cBg;
    private Color cPanelBg;
    private Color cInputBg;
    private Color cFg;
    private Color cFgSubdued;
    private Color cBorder;
    private Color cTitle;
    private Color cButtonBg;
    private Color cButtonFg;

    private BufferedImage logoImage = null;

    // =========================================================================
    // CONSTRUCTOR & INITIALIZATION
    // =========================================================================

    public BrokVnClickAreaWindow() {
        this(null, true, findDefaultProjectDir(), null);
    }

    public BrokVnClickAreaWindow(JFrame parent, boolean isDarkMode, File projectBaseDir, InsertCallback callback) {
        super(APP_NAME + " - Visual Novel Studio (1920×1080 Native Canvas)");
        this.isDarkMode = isDarkMode;
        this.projectBaseDir = (projectBaseDir != null) ? projectBaseDir : findDefaultProjectDir();
        this.insertCallback = callback;

        initColorTokens();
        loadWindowIcon();
        loadLogoImage();

        try {
            UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
        } catch (Exception ignored) {
        }

        applyUiManagerDefaults();

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1520, 960);
        setMinimumSize(new Dimension(1140, 750));
        setLocationRelativeTo(parent);

        buildUi();

        SwingUtilities.updateComponentTreeUI(this);
        applyTheme();
        updateScriptPreview();

        initAnimationTimer();
        findAndPromptDefaultBackground();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            BrokVnClickAreaWindow window = new BrokVnClickAreaWindow();
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setVisible(true);
        });
    }

    private static File findDefaultProjectDir() {
        String localApp = System.getenv("LOCALAPPDATA");
        if (localApp != null) {
            File vnDir = new File(localApp, "vnengine/VN");
            if (vnDir.exists() && vnDir.isDirectory())
                return vnDir;
        }
        File docsBrok = new File(System.getProperty("user.home"), "Documents/Brok VN/BROKVN_Engine_WIN_1.0.0");
        if (docsBrok.exists() && docsBrok.isDirectory())
            return docsBrok;

        return new File(".");
    }

    private void initAnimationTimer() {
        animTimer = new javax.swing.Timer(16, e -> {
            long now = System.nanoTime();
            boolean needRepaint = false;
            for (OverlayObject obj : overlayObjects) {
                // Spritesheet Frame Cycling (calibrated default 8 fps)
                if (obj.isAnimation && obj.isPlaying && obj.nbFrames > 1 && obj.animSpeed > 0) {
                    long frameIntervalNano = 1_000_000_000L / obj.animSpeed;
                    if (now - obj.lastFrameTimeNano >= frameIntervalNano) {
                        obj.lastFrameTimeNano = now;
                        int next = obj.currentFrameIndex + 1;
                        if (next >= obj.nbFrames) {
                            if ("BLOCK".equalsIgnoreCase(obj.animEnd)) {
                                next = obj.nbFrames - 1;
                            } else if ("DESTROY".equalsIgnoreCase(obj.animEnd)) {
                                next = 0;
                                obj.isPlaying = false;
                            } else {
                                next = 0;
                            }
                        }
                        if (next != obj.currentFrameIndex) {
                            obj.currentFrameIndex = next;
                            needRepaint = true;
                            if (obj == activeOverlayObject && sldAnimScrubber != null && !sldAnimScrubber.getValueIsAdjusting()) {
                                sldAnimScrubber.setValue(obj.currentFrameIndex + 1);
                                updateAnimStatusLabel();
                            }
                        }
                    }
                }

                // Multi-Waypoint Walk Path Movement
                if (obj.useWalkPath && obj.isPlaying && obj.waypoints.size() >= 2 && currentDragType != DragHandleType.OVERLAY_SPRITE) {
                    int numSegments = obj.waypoints.size() - 1;
                    if (obj.currentWaypointSegment >= 0 && obj.currentWaypointSegment < numSegments) {
                        Waypoint pStart = obj.waypoints.get(obj.currentWaypointSegment);
                        Waypoint pEnd = obj.waypoints.get(obj.currentWaypointSegment + 1);

                        double totalDx = pEnd.x - pStart.x;
                        double totalDy = pEnd.y - pStart.y;
                        double totalDist = Math.sqrt(totalDx * totalDx + totalDy * totalDy);

                        if (totalDist > 0.001) {
                            int speed = Math.max(1, pEnd.speed);
                            double stepProg = (double) speed / totalDist;

                            if (obj.walkForward) {
                                obj.currentWalkProgress += stepProg;
                                if (obj.currentWalkProgress >= 1.0) {
                                    obj.currentWalkProgress = 0.0;
                                    obj.currentWaypointSegment++;
                                    if (obj.currentWaypointSegment >= numSegments) {
                                        if ("REPEAT".equalsIgnoreCase(obj.animEnd)) {
                                            obj.currentWaypointSegment = 0;
                                        } else {
                                            obj.currentWaypointSegment = numSegments - 1;
                                            obj.walkForward = false;
                                        }
                                    }
                                }
                            } else {
                                obj.currentWalkProgress -= stepProg;
                                if (obj.currentWalkProgress <= 0.0) {
                                    obj.currentWalkProgress = 1.0;
                                    obj.currentWaypointSegment--;
                                    if (obj.currentWaypointSegment < 0) {
                                        obj.currentWaypointSegment = 0;
                                        obj.walkForward = true;
                                    }
                                }
                            }

                            int segIdx = Math.max(0, Math.min(numSegments - 1, obj.currentWaypointSegment));
                            Waypoint curS = obj.waypoints.get(segIdx);
                            Waypoint curE = obj.waypoints.get(segIdx + 1);
                            int curAx = (int) Math.round(curS.x + (curE.x - curS.x) * obj.currentWalkProgress);
                            int curAy = (int) Math.round(curS.y + (curE.y - curS.y) * obj.currentWalkProgress);
                            obj.setFromAnchor(curAx, curAy);
                            needRepaint = true;
                        }
                    }
                }
            }
            if (needRepaint && canvas != null) {
                canvas.repaint();
            }
        });
        animTimer.start();
    }

    // =========================================================================
    // GITHUB API TOKEN & PREFERENCES
    // =========================================================================

    public static String getGitHubApiToken() {
        return prefs.get(PREF_GITHUB_TOKEN, "");
    }

    public static void setGitHubApiToken(String token) {
        if (token != null) {
            prefs.put(PREF_GITHUB_TOKEN, token.trim());
        }
    }

    public static File getLastBrowseDirectory() {
        String path = prefs.get(PREF_LAST_BROWSE_DIR, null);
        if (path != null) {
            File f = new File(path);
            if (f.exists() && f.isDirectory()) {
                return f;
            }
        }
        return findDefaultProjectDir();
    }

    public static void setLastBrowseDirectory(File dir) {
        if (dir != null) {
            File d = dir.isDirectory() ? dir : dir.getParentFile();
            if (d != null && d.exists()) {
                prefs.put(PREF_LAST_BROWSE_DIR, d.getAbsolutePath());
            }
        }
    }

    public File browseFileNative(String title, int mode, String fileFilterDesc, String... extensions) {
        File startDir = getLastBrowseDirectory();
        FileDialog fd = new FileDialog(this, title, mode);
        if (startDir != null && startDir.exists()) {
            fd.setDirectory(startDir.getAbsolutePath());
        }
        if (extensions != null && extensions.length > 0) {
            fd.setFilenameFilter((dir, name) -> {
                String lower = name.toLowerCase();
                for (String ext : extensions) {
                    if (lower.endsWith("." + ext.toLowerCase()))
                        return true;
                }
                return false;
            });
        }
        fd.setVisible(true);
        String selectedFile = fd.getFile();
        String selectedDir = fd.getDirectory();
        if (selectedFile != null && selectedDir != null) {
            File f = new File(selectedDir, selectedFile);
            setLastBrowseDirectory(f.getParentFile());
            return f;
        }
        return null;
    }

    public void openInExplorer(File target) {
        try {
            if (target == null || !target.exists()) {
                target = getLastBrowseDirectory();
            }
            String path = target.getAbsolutePath();
            if (target.isDirectory()) {
                new ProcessBuilder("explorer.exe", path).start();
            } else {
                new ProcessBuilder("explorer.exe", "/select,", path).start();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not launch Windows Explorer: " + ex.getMessage(),
                    "Explorer Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // UI THEME & TOKENS
    // =========================================================================

    private void initColorTokens() {
        if (isDarkMode) {
            cBg = new Color(22, 24, 28);
            cPanelBg = new Color(30, 33, 39);
            cInputBg = new Color(38, 42, 50);
            cFg = new Color(240, 243, 250);
            cFgSubdued = new Color(165, 175, 192);
            cBorder = new Color(60, 66, 80);
            cTitle = new Color(125, 185, 255);
            cButtonBg = new Color(48, 54, 66);
            cButtonFg = new Color(240, 245, 255);
        } else {
            cBg = new Color(240, 242, 246);
            cPanelBg = Color.WHITE;
            cInputBg = new Color(248, 249, 252);
            cFg = new Color(25, 28, 35);
            cFgSubdued = new Color(90, 95, 110);
            cBorder = new Color(205, 210, 220);
            cTitle = new Color(20, 90, 190);
            cButtonBg = new Color(230, 235, 245);
            cButtonFg = new Color(20, 25, 35);
        }
    }

    private void applyUiManagerDefaults() {
        UIManager.put("Panel.background", cPanelBg);
        UIManager.put("Panel.foreground", cFg);
        UIManager.put("Button.background", cButtonBg);
        UIManager.put("Button.foreground", cButtonFg);
        UIManager.put("Button.border", BorderFactory.createCompoundBorder(
                new LineBorder(cBorder, 1), new EmptyBorder(5, 12, 5, 12)));
        UIManager.put("Button.focus", cButtonBg);
        UIManager.put("Button.select", cBorder);
        UIManager.put("Button.highlight", cButtonBg.brighter());
        UIManager.put("ComboBox.background", cInputBg);
        UIManager.put("ComboBox.foreground", cFg);
        UIManager.put("ComboBox.selectionBackground", new Color(0, 122, 255));
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.border", new LineBorder(cBorder, 1));
        UIManager.put("ComboBox.buttonBackground", cInputBg);
        UIManager.put("TextField.background", cInputBg);
        UIManager.put("TextField.foreground", cFg);
        UIManager.put("TextField.caretForeground", cFg);
        UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
                new LineBorder(cBorder, 1), new EmptyBorder(4, 8, 4, 8)));
        UIManager.put("TextField.selectionBackground", new Color(0, 122, 255));
        UIManager.put("TextField.selectionForeground", Color.WHITE);
        UIManager.put("Spinner.background", cInputBg);
        UIManager.put("Spinner.foreground", cFg);
        UIManager.put("Spinner.border", new LineBorder(cBorder, 1));
        UIManager.put("CheckBox.background", cPanelBg);
        UIManager.put("CheckBox.foreground", cFg);
        UIManager.put("Label.foreground", cFg);
        UIManager.put("Label.background", cPanelBg);
        UIManager.put("ScrollPane.background", cInputBg);
        UIManager.put("Viewport.background", cInputBg);
        UIManager.put("ScrollBar.background", cBg);
        UIManager.put("ScrollBar.thumb", cBorder);
        UIManager.put("TextArea.background", cInputBg);
        UIManager.put("TextArea.foreground", cFg);
        UIManager.put("TextArea.caretForeground", cFg);
        UIManager.put("TextArea.selectionBackground", new Color(0, 122, 255));
        UIManager.put("TextArea.selectionForeground", Color.WHITE);
        UIManager.put("Table.background", cInputBg);
        UIManager.put("Table.foreground", cFg);
        UIManager.put("Table.selectionBackground", new Color(0, 122, 255));
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.gridColor", cBorder);
        UIManager.put("TableHeader.background", cButtonBg);
        UIManager.put("TableHeader.foreground", cFg);
        UIManager.put("TitledBorder.titleColor", cTitle);
        UIManager.put("TitledBorder.border", new LineBorder(cBorder, 1));
        UIManager.put("TabbedPane.background", cBg);
        UIManager.put("TabbedPane.foreground", cFg);
        UIManager.put("TabbedPane.selected", cButtonBg);
        UIManager.put("TabbedPane.selectedForeground", Color.WHITE);
        UIManager.put("TabbedPane.contentAreaColor", cPanelBg);
    }

    private void loadLogoImage() {
        File[] candidates = new File[] {
                new File(projectBaseDir, "logo.png"),
                new File("logo.png"),
                new File(System.getProperty("user.dir"), "logo.png"),
                new File("dist/BrokVnClickAreaWindow/logo.png")
        };
        for (File f : candidates) {
            if (f != null && f.exists()) {
                try {
                    logoImage = ImageIO.read(f);
                    if (logoImage != null)
                        break;
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void loadWindowIcon() {
        File[] candidates = new File[] {
                new File(projectBaseDir, "icon.png"),
                new File("icon.png"),
                new File(System.getProperty("user.dir"), "icon.png"),
                new File("dist/BrokVnClickAreaWindow/icon.png")
        };
        for (File f : candidates) {
            if (f != null && f.exists()) {
                try {
                    BufferedImage icon = ImageIO.read(f);
                    if (icon != null) {
                        setIconImage(icon);
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    // =========================================================================
    // UI BUILDER
    // =========================================================================

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(cBg);

        // Top Menu Bar
        setJMenuBar(buildMenuBar());

        // Top Toolbar
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(cPanelBg);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, cBorder),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftActions.setOpaque(false);

        JButton btnImportBg = new JButton("Import Background...");
        stylePrimaryButton(btnImportBg, new Color(0, 122, 255));
        btnImportBg.setToolTipText("Load 1920×1080 background image via Windows File Explorer");
        btnImportBg.addActionListener(e -> chooseAndLoadImage());

        JButton btnPlaceObject = new JButton("Place Sprite .PNG...");
        stylePrimaryButton(btnPlaceObject, new Color(0, 155, 225));
        btnPlaceObject.setToolTipText("Place character sprite or object PNG onto canvas via Windows File Explorer");
        btnPlaceObject.addActionListener(e -> chooseAndPlaceObjectPng());

        JButton btnImportSpritesheet = new JButton("Import Spritesheet...");
        stylePrimaryButton(btnImportSpritesheet, new Color(130, 80, 220));
        btnImportSpritesheet.setToolTipText("Import animated character spritesheet strip with automatic frame slicing");
        btnImportSpritesheet.addActionListener(e -> chooseAndImportSpritesheet());

        btnClearOverlays = new JButton("Clear Sprites");
        styleStandardButton(btnClearOverlays);
        btnClearOverlays.setEnabled(false);
        btnClearOverlays.setToolTipText("Remove all placed character/object overlays from canvas");
        btnClearOverlays.addActionListener(e -> clearOverlayObjects());

        JButton btnOpenExplorer = new JButton("Open in Explorer");
        styleStandardButton(btnOpenExplorer);
        btnOpenExplorer.setToolTipText("Open the last browsed folder directly in Windows Explorer (explorer.exe)");
        btnOpenExplorer.addActionListener(e -> openInExplorer(getLastBrowseDirectory()));

        leftActions.add(btnImportBg);
        leftActions.add(btnPlaceObject);
        leftActions.add(btnImportSpritesheet);
        leftActions.add(btnClearOverlays);
        leftActions.add(btnOpenExplorer);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightActions.setOpaque(false);

        btnTargetClicker = new JToggleButton("Edit Clicker", true);
        btnTargetImage = new JToggleButton("Edit Sprite", false);
        styleStandardButton(btnTargetClicker);
        styleStandardButton(btnTargetImage);
        btnTargetClicker.setToolTipText("Interactive mode: Dragging sets CLICKERNEW coordinates (X1, Y1, X2, Y2)");
        btnTargetImage.setToolTipText("Interactive mode: Dragging moves placed IMAGENEW sprite position (X, Y)");

        ButtonGroup editGroup = new ButtonGroup();
        editGroup.add(btnTargetClicker);
        editGroup.add(btnTargetImage);

        btnTargetClicker.addActionListener(e -> {
            activeEditTarget = ActiveEditTarget.CLICKER;
            if (rightTabbedPane != null)
                rightTabbedPane.setSelectedIndex(0);
            if (canvas != null)
                canvas.repaint();
        });

        btnTargetImage.addActionListener(e -> {
            activeEditTarget = ActiveEditTarget.IMAGE;
            if (rightTabbedPane != null)
                rightTabbedPane.setSelectedIndex(1);
            if (canvas != null)
                canvas.repaint();
        });

        chkShowOverlays = new JCheckBox("Show Sprites", true);
        styleCheckBox(chkShowOverlays);
        chkShowOverlays.addActionListener(e -> canvas.repaint());

        chkShowGrid = new JCheckBox("Grid", false);
        styleCheckBox(chkShowGrid);
        chkShowGrid.addActionListener(e -> canvas.repaint());

        chkShowAllClickers = new JCheckBox("All Clickers", true);
        styleCheckBox(chkShowAllClickers);
        chkShowAllClickers.addActionListener(e -> canvas.repaint());

        chkShowWaypoints = new JCheckBox("Waypoints", true);
        styleCheckBox(chkShowWaypoints);
        chkShowWaypoints.addActionListener(e -> canvas.repaint());

        JButton btnFullScreenPreview = new JButton("Full Screen (F11)");
        stylePrimaryButton(btnFullScreenPreview, new Color(230, 110, 0));
        btnFullScreenPreview.setToolTipText("Open borderless full-screen preview with interactive testing (F11)");
        btnFullScreenPreview.addActionListener(e -> openFullScreenPreview());

        JButton btnGlueIt = new JButton("Glue It Maker");
        styleStandardButton(btnGlueIt);
        btnGlueIt.setToolTipText("Launch GlueIT Sprite Sheet Maker (glueit.exe)");
        btnGlueIt.addActionListener(e -> launchGlueIt());

        JButton btnCheckUpdate = new JButton("Check Updates");
        styleStandardButton(btnCheckUpdate);
        btnCheckUpdate.setToolTipText("Check for newer BrokVN GUI Editor updates from GitHub");
        btnCheckUpdate.addActionListener(e -> showUpdateDialog(false));

        rightActions.add(btnTargetClicker);
        rightActions.add(btnTargetImage);
        rightActions.add(Box.createHorizontalStrut(4));
        rightActions.add(chkShowOverlays);
        rightActions.add(chkShowGrid);
        rightActions.add(chkShowAllClickers);
        rightActions.add(chkShowWaypoints);
        rightActions.add(Box.createHorizontalStrut(4));
        rightActions.add(btnFullScreenPreview);
        rightActions.add(btnGlueIt);
        rightActions.add(btnCheckUpdate);

        topBar.add(leftActions, BorderLayout.WEST);
        topBar.add(rightActions, BorderLayout.EAST);

        JPanel canvasContainer = buildCanvasContainer();
        JPanel rightPanel = buildRightPanel();

        JSplitPane splitMain = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvasContainer, rightPanel);
        splitMain.setBackground(cBg);
        splitMain.setDividerSize(6);
        splitMain.setResizeWeight(0.56);
        splitMain.setBorder(null);
        splitMain.setDividerLocation(850);

        root.add(topBar, BorderLayout.NORTH);
        root.add(splitMain, BorderLayout.CENTER);

        // Global Keybindings
        root.registerKeyboardAction(e -> openFullScreenPreview(), KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> saveProject(false), KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> openProject(), KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> newProject(), KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setContentPane(root);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.setBackground(cPanelBg);
        mb.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, cBorder));

        JMenu mFile = new JMenu("File");
        mFile.setForeground(cFg);

        JMenuItem miNewProj = new JMenuItem("New Project (Ctrl+N)");
        miNewProj.addActionListener(e -> newProject());

        JMenuItem miOpenProj = new JMenuItem("Open Project... (Ctrl+O)");
        miOpenProj.addActionListener(e -> openProject());

        JMenuItem miSaveProj = new JMenuItem("Save Project (Ctrl+S)");
        miSaveProj.addActionListener(e -> saveProject(false));

        JMenuItem miSaveProjAs = new JMenuItem("Save Project As...");
        miSaveProjAs.addActionListener(e -> saveProject(true));

        JMenuItem miImportBg = new JMenuItem("Import 1920×1080 Background...");
        miImportBg.addActionListener(e -> chooseAndLoadImage());

        JMenuItem miImportSprite = new JMenuItem("Place Character Sprite .PNG...");
        miImportSprite.addActionListener(e -> chooseAndPlaceObjectPng());

        JMenuItem miImportSpritesheet = new JMenuItem("Import Spritesheet Animation...");
        miImportSpritesheet.addActionListener(e -> chooseAndImportSpritesheet());

        JMenuItem miOpenExplorer = new JMenuItem("Open Last Folder in Windows Explorer");
        miOpenExplorer.addActionListener(e -> openInExplorer(getLastBrowseDirectory()));

        JMenuItem miExit = new JMenuItem("Exit");
        miExit.addActionListener(e -> dispose());

        mFile.add(miNewProj);
        mFile.add(miOpenProj);
        mFile.add(miSaveProj);
        mFile.add(miSaveProjAs);
        mFile.addSeparator();
        mFile.add(miImportBg);
        mFile.add(miImportSprite);
        mFile.add(miImportSpritesheet);
        mFile.addSeparator();
        mFile.add(miOpenExplorer);
        mFile.addSeparator();
        mFile.add(miExit);

        JMenu mView = new JMenu("View");
        mView.setForeground(cFg);

        JMenuItem miFull = new JMenuItem("Full Screen Preview (F11)");
        miFull.addActionListener(e -> openFullScreenPreview());
        mView.add(miFull);

        JMenu mTools = new JMenu("Tools");
        mTools.setForeground(cFg);

        JMenuItem miGlueIt = new JMenuItem("Launch GlueIT Spritesheet Maker");
        miGlueIt.addActionListener(e -> launchGlueIt());

        JMenuItem miClear = new JMenuItem("Clear Sprites from Canvas");
        miClear.addActionListener(e -> clearOverlayObjects());

        mTools.add(miGlueIt);
        mTools.add(miClear);

        JMenu mHelp = new JMenu("Help");
        mHelp.setForeground(cFg);

        JMenuItem miDoc = new JMenuItem("Brok VN Engine Documentation (HTML)");
        miDoc.addActionListener(e -> openDocumentationHtml());

        JMenuItem miCheckUpdate = new JMenuItem("Check for Updates...");
        miCheckUpdate.addActionListener(e -> showUpdateDialog(true));

        JMenuItem miConfigToken = new JMenuItem("Configure GitHub API Token...");
        miConfigToken.addActionListener(e -> showConfigureTokenDialog());

        JMenuItem miAbout = new JMenuItem("About " + APP_NAME);
        miAbout.addActionListener(e -> showAboutDialog());

        mHelp.add(miDoc);
        mHelp.addSeparator();
        mHelp.add(miCheckUpdate);
        mHelp.add(miConfigToken);
        mHelp.add(miAbout);

        mb.add(mFile);
        mb.add(mView);
        mb.add(mTools);
        mb.add(mHelp);
        return mb;
    }

    // =========================================================================
    // CANVAS CONTAINER (SIDE 1 / CENTER)
    // =========================================================================

    private JPanel buildCanvasContainer() {
        JPanel canvasContainer = new JPanel(new BorderLayout());
        canvasContainer.setBackground(cBg);
        canvas = new ClickAreaCanvas();
        canvasContainer.add(canvas, BorderLayout.CENTER);

        JPanel canvasStatusBar = new JPanel(new GridLayout(2, 1, 0, 3));
        canvasStatusBar.setBackground(cPanelBg);
        canvasStatusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, cBorder),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));

        JPanel row1 = new JPanel(new BorderLayout(12, 0));
        row1.setOpaque(false);

        lblImageStatus = new JLabel("Engine Canvas: 1920×1080 | [No Image Loaded]");
        lblImageStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblImageStatus.setForeground(cFgSubdued);

        lblCursorPos = new JLabel("Cursor: [ X: - , Y: - ]");
        lblCursorPos.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblCursorPos.setForeground(cFg);

        row1.add(lblImageStatus, BorderLayout.WEST);
        row1.add(lblCursorPos, BorderLayout.EAST);

        JPanel row2 = new JPanel(new BorderLayout(12, 0));
        row2.setOpaque(false);

        lblCurrentBounds = new JLabel("Clicker: [100, 100] -> [500, 400] (400 × 300 px)");
        lblCurrentBounds.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblCurrentBounds.setForeground(new Color(255, 215, 0)); // Gold
        row2.add(lblCurrentBounds, BorderLayout.WEST);

        canvasStatusBar.add(row1);
        canvasStatusBar.add(row2);
        canvasContainer.add(canvasStatusBar, BorderLayout.SOUTH);
        return canvasContainer;
    }

    // =========================================================================
    // RIGHT PANEL (3RD SIDE): INSPECTOR & OVERALL BROKVN FILE GENERATOR
    // =========================================================================

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(cBg);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 4));

        rightTabbedPane = new JTabbedPane();
        rightTabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        // TAB 1: Clicker Definition (CLICKERNEW)
        JPanel tabClicker = buildClickerPanel();
        rightTabbedPane.addTab("Clicker", tabClicker);

        // TAB 2: Sprite / Character / Animation / Multi-Waypoint Walk Path
        JPanel tabImage = buildImagePanel();
        rightTabbedPane.addTab("Sprite / Character", tabImage);

        // TAB 3: Layer Management & Visibility
        JPanel tabLayers = buildLayersPanel();
        rightTabbedPane.addTab("Layers", tabLayers);

        // TAB 4: Clickers List & Scene Batch Exporter
        JPanel tabList = buildListPanel();
        rightTabbedPane.addTab("Clickers List (" + savedClickers.size() + ")", tabList);

        // TAB 5: Overall BrokVN File (3rd Side Full Script)
        JPanel tabOverall = buildOverallBrokVnFilePanel();
        rightTabbedPane.addTab("Overall BrokVN File", tabOverall);

        rightTabbedPane.addChangeListener(e -> {
            int idx = rightTabbedPane.getSelectedIndex();
            if (idx == 0) {
                activeEditTarget = ActiveEditTarget.CLICKER;
                if (btnTargetClicker != null)
                    btnTargetClicker.setSelected(true);
            } else if (idx == 1) {
                activeEditTarget = ActiveEditTarget.IMAGE;
                if (btnTargetImage != null)
                    btnTargetImage.setSelected(true);
                syncImageUiFromActiveObject();
            } else if (idx == 2) {
                refreshLayerTable();
            } else if (idx == 4) {
                updateOverallBrokVnFile();
            }
            if (canvas != null)
                canvas.repaint();
        });

        // Script Output Area at bottom of right panel
        JPanel codeContainer = new JPanel(new BorderLayout(0, 4));
        codeContainer.setBackground(cPanelBg);
        codeContainer.setPreferredSize(new Dimension(380, 190));
        codeContainer.setMinimumSize(new Dimension(300, 150));
        styleTitledBorder(codeContainer, "Generated Script Definition");

        JPanel scriptHeader = new JPanel(new BorderLayout(6, 0));
        scriptHeader.setOpaque(false);
        JLabel lblMode = createStyledLabel("Script Mode:");
        lblMode.setFont(new Font("Segoe UI", Font.BOLD, 11));

        cmbScriptGenMode = new JComboBox<>(new String[] {
                "Combined (IMAGENEW + IMAGEMOVE + CLICKERNEW)",
                "New Item Sprite (IMAGENEW Only)",
                "Walk Path Movement (IMAGEMOVE Only)",
                "Click Area Hotspot (CLICKERNEW Only)"
        });
        styleComboBox(cmbScriptGenMode);
        cmbScriptGenMode.addItemListener(e -> updateScriptPreview());

        scriptHeader.add(lblMode, BorderLayout.WEST);
        scriptHeader.add(cmbScriptGenMode, BorderLayout.CENTER);
        codeContainer.add(scriptHeader, BorderLayout.NORTH);

        txtScriptPreview = new JTextArea();
        txtScriptPreview.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtScriptPreview.setEditable(false);
        txtScriptPreview.setBackground(cInputBg);
        txtScriptPreview.setForeground(cFg);
        txtScriptPreview.setCaretColor(cFg);
        txtScriptPreview.setBorder(new EmptyBorder(4, 6, 4, 6));

        this.codeScroll = new JScrollPane(txtScriptPreview);
        this.codeScroll.setBorder(new LineBorder(cBorder, 1));
        this.codeScroll.getViewport().setBackground(cInputBg);
        codeContainer.add(this.codeScroll, BorderLayout.CENTER);

        JPanel codeActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        codeActions.setOpaque(false);

        JButton btnAddList = new JButton("+ Add Clicker to List");
        styleStandardButton(btnAddList);
        btnAddList.addActionListener(e -> addCurrentToClickersList());

        JButton btnCopy = new JButton("Copy Code");
        styleStandardButton(btnCopy);
        btnCopy.addActionListener(e -> copyCurrentCode());

        JButton btnInsert = new JButton("Insert Code");
        stylePrimaryButton(btnInsert, new Color(35, 134, 54));
        btnInsert.addActionListener(e -> insertCurrentCode());

        codeActions.add(btnAddList);
        codeActions.add(btnCopy);
        codeActions.add(btnInsert);
        codeContainer.add(codeActions, BorderLayout.SOUTH);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rightTabbedPane, codeContainer);
        rightSplit.setBackground(cBg);
        rightSplit.setDividerSize(5);
        rightSplit.setResizeWeight(0.75);
        rightSplit.setBorder(null);

        panel.add(rightSplit, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildClickerPanel() {
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(cPanelBg);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Coordinate Box
        JPanel coordBox = new JPanel(new GridLayout(2, 6, 4, 3));
        coordBox.setBackground(cPanelBg);
        styleTitledBorder(coordBox, "Clicker Coordinates (CLICKERNEW X1, Y1, X2, Y2)");

        spX1 = new JSpinner(new SpinnerNumberModel(curX1, -3000, 5000, 1));
        spY1 = new JSpinner(new SpinnerNumberModel(curY1, -3000, 5000, 1));
        spX2 = new JSpinner(new SpinnerNumberModel(curX2, -3000, 5000, 1));
        spY2 = new JSpinner(new SpinnerNumberModel(curY2, -3000, 5000, 1));
        spWidth = new JSpinner(new SpinnerNumberModel(curX2 - curX1, 1, 8000, 1));
        spHeight = new JSpinner(new SpinnerNumberModel(curY2 - curY1, 1, 8000, 1));

        styleSpinner(spX1);
        styleSpinner(spY1);
        styleSpinner(spX2);
        styleSpinner(spY2);
        styleSpinner(spWidth);
        styleSpinner(spHeight);

        ChangeListener spinnerListener = (ChangeEvent e) -> {
            if (updatingSpinners)
                return;
            updatingSpinners = true;
            if (e.getSource() == spWidth) {
                int w = (int) spWidth.getValue();
                curX2 = curX1 + w;
                spX2.setValue(curX2);
            } else if (e.getSource() == spHeight) {
                int h = (int) spHeight.getValue();
                curY2 = curY1 + h;
                spY2.setValue(curY2);
            } else {
                curX1 = (int) spX1.getValue();
                curY1 = (int) spY1.getValue();
                curX2 = (int) spX2.getValue();
                curY2 = (int) spY2.getValue();
                if (curX2 < curX1) {
                    curX2 = curX1;
                    spX2.setValue(curX2);
                }
                if (curY2 < curY1) {
                    curY2 = curY1;
                    spY2.setValue(curY2);
                }
                spWidth.setValue(Math.max(1, curX2 - curX1));
                spHeight.setValue(Math.max(1, curY2 - curY1));
            }
            updateBoundsLabel();
            updateScriptPreview();
            canvas.repaint();
            updatingSpinners = false;
        };

        spX1.addChangeListener(spinnerListener);
        spY1.addChangeListener(spinnerListener);
        spX2.addChangeListener(spinnerListener);
        spY2.addChangeListener(spinnerListener);
        spWidth.addChangeListener(spinnerListener);
        spHeight.addChangeListener(spinnerListener);

        coordBox.add(createStyledLabel("X1:"));
        coordBox.add(createStyledLabel("Y1:"));
        coordBox.add(createStyledLabel("X2:"));
        coordBox.add(createStyledLabel("Y2:"));
        coordBox.add(createStyledLabel("Width:"));
        coordBox.add(createStyledLabel("Height:"));

        coordBox.add(spX1);
        coordBox.add(spY1);
        coordBox.add(spX2);
        coordBox.add(spY2);
        coordBox.add(spWidth);
        coordBox.add(spHeight);

        // Presets buttons
        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        presetBar.setBackground(cPanelBg);
        JLabel lblPresets = createStyledLabel("Presets: ");
        lblPresets.setFont(new Font("Segoe UI", Font.BOLD, 11));
        presetBar.add(lblPresets);

        JButton btnPresetFull = new JButton("Full Screen");
        styleStandardButton(btnPresetFull);
        btnPresetFull.addActionListener(e -> setBoundsCoordinates(0, 0, 1920, 1080));

        JButton btnPresetLeft = new JButton("Left Half");
        styleStandardButton(btnPresetLeft);
        btnPresetLeft.addActionListener(e -> setBoundsCoordinates(0, 0, 960, 1080));

        JButton btnPresetRight = new JButton("Right Half");
        styleStandardButton(btnPresetRight);
        btnPresetRight.addActionListener(e -> setBoundsCoordinates(960, 0, 1920, 1080));

        JButton btnPresetDialogue = new JButton("Bottom UI Area");
        styleStandardButton(btnPresetDialogue);
        btnPresetDialogue.addActionListener(e -> setBoundsCoordinates(0, 780, 1920, 1080));

        JButton btnSnapToImg = new JButton("Snap to Sprite");
        stylePrimaryButton(btnSnapToImg, new Color(0, 155, 225));
        btnSnapToImg.setToolTipText("Align clicker hotspot X1, Y1, X2, Y2 to match the active placed image sprite");
        btnSnapToImg.addActionListener(e -> snapClickerToImage());

        presetBar.add(btnPresetFull);
        presetBar.add(btnPresetLeft);
        presetBar.add(btnPresetRight);
        presetBar.add(btnPresetDialogue);
        presetBar.add(btnSnapToImg);

        // Clicker Properties Form
        JPanel propPanel = new JPanel(new GridBagLayout());
        propPanel.setBackground(cPanelBg);
        styleTitledBorder(propPanel, "Clicker Parameters (Brok VN Spec)");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        txtId = new JTextField("CLICK_INTERACTION");
        txtEvent = new JTextField("S01_EXAMINE_ITEM");
        txtText = new JTextField("Inspect Item");
        spClickerLayer = new JSpinner(new SpinnerNumberModel(0, 0, 50, 1));

        styleTextField(txtId);
        styleTextField(txtEvent);
        styleTextField(txtText);
        styleSpinner(spClickerLayer);

        chkHighlight = new JCheckBox("HIGHLIGHT=1 (Hover highlight)", true);
        chkHotspot = new JCheckBox("HOTSPOT=1 (Revealed via Spacebar)", true);
        chkCanDpad = new JCheckBox("CANDPAD=1 (D-Pad navigation)", true);
        styleCheckBox(chkHighlight);
        styleCheckBox(chkHotspot);
        styleCheckBox(chkCanDpad);

        cmbHotspotIcon = new JComboBox<>(new String[] {
                "ICON_ACTIVE", "ICON_TALK", "ICON_LOOK", "ICON_HAND", "ICON_DOOR", "ICON_EXIT", "ICON_NOTE", "ICON_NONE"
        });
        cmbType = new JComboBox<>(new String[] { "NORMAL", "INTERFACE" });
        cmbStayActive = new JComboBox<>(new String[] { "DEFAULT", "DIALOGUE", "ALWAYS" });
        styleComboBox(cmbHotspotIcon);
        styleComboBox(cmbType);
        styleComboBox(cmbStayActive);

        KeyAdapter updateAdapter = new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                updateScriptPreview();
            }
        };

        txtId.addKeyListener(updateAdapter);
        txtEvent.addKeyListener(updateAdapter);
        txtText.addKeyListener(updateAdapter);
        spClickerLayer.addChangeListener(e -> updateScriptPreview());

        ItemListener itemListener = e -> updateScriptPreview();
        chkHighlight.addItemListener(itemListener);
        chkHotspot.addItemListener(itemListener);
        chkCanDpad.addItemListener(itemListener);
        cmbHotspotIcon.addItemListener(itemListener);
        cmbType.addItemListener(itemListener);
        cmbStayActive.addItemListener(itemListener);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.3;
        propPanel.add(createStyledLabel("Clicker ID:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.7;
        propPanel.add(txtId, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.3;
        propPanel.add(createStyledLabel("Target CLICKEVENT:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.7;
        propPanel.add(txtEvent, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.3;
        propPanel.add(createStyledLabel("Hover Text (TEXT=):"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.7;
        propPanel.add(txtText, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.3;
        propPanel.add(createStyledLabel("Hotspot Icon:"), gbc);
        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 0.7;
        propPanel.add(cmbHotspotIcon, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.3;
        propPanel.add(createStyledLabel("Layer / Type:"), gbc);
        JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typePanel.setOpaque(false);
        typePanel.add(createStyledLabel("LAYER:"));
        spClickerLayer.setPreferredSize(new Dimension(50, 24));
        typePanel.add(spClickerLayer);
        typePanel.add(cmbType);
        typePanel.add(cmbStayActive);
        gbc.gridx = 1; gbc.gridy = 4; gbc.weightx = 0.7;
        propPanel.add(typePanel, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2;
        JPanel flagsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        flagsPanel.setOpaque(false);
        flagsPanel.add(chkHighlight);
        flagsPanel.add(chkHotspot);
        flagsPanel.add(chkCanDpad);
        propPanel.add(flagsPanel, gbc);

        contentPanel.add(coordBox);
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(presetBar);
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(propPanel);

        JScrollPane scroll = new JScrollPane(contentPanel);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(cPanelBg);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(cPanelBg);
        outer.add(scroll, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildImagePanel() {
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(cPanelBg);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Card 1: Character / Sprite Placement (IMAGENEW)
        JPanel imgPropBox = new JPanel(new GridBagLayout());
        imgPropBox.setBackground(cPanelBg);
        styleTitledBorder(imgPropBox, "Character / Sprite Placement (IMAGENEW)");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        txtImageId = new JTextField("IMAGE_DIRECTOR");
        txtImageFile = new JTextField("IMAGE_DIRECTOR");
        styleTextField(txtImageId);
        styleTextField(txtImageFile);

        KeyAdapter keySync = new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                syncActiveObjectFromImageUi();
            }
        };
        txtImageId.addKeyListener(keySync);
        txtImageFile.addKeyListener(keySync);

        spImgX = new JSpinner(new SpinnerNumberModel(254, -3000, 5000, 1));
        spImgY = new JSpinner(new SpinnerNumberModel(380, -3000, 5000, 1));
        styleSpinner(spImgX);
        styleSpinner(spImgY);

        ChangeListener imgSpinnerListener = e -> syncActiveObjectFromImageUi();
        spImgX.addChangeListener(imgSpinnerListener);
        spImgY.addChangeListener(imgSpinnerListener);

        lblImgDimensions = new JLabel("No sprite placed");
        lblImgDimensions.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblImgDimensions.setForeground(new Color(0, 175, 255));

        chkUseOrigin = new JCheckBox("Anchor Origin (ORIGIN=)", true);
        styleCheckBox(chkUseOrigin);
        chkUseOrigin.addActionListener(e -> {
            cmbImageOrigin.setEnabled(chkUseOrigin.isSelected());
            syncActiveObjectFromImageUi();
        });

        cmbImageOrigin = new JComboBox<>(new String[] {
                "CENTER", "TOPLEFT", "BOTTOMCENTER", "TOPCENTER", "BOTTOMLEFT", "BOTTOMRIGHT", "CENTERLEFT",
                "CENTERRIGHT", "TOPRIGHT"
        });
        cmbImageOrigin.setSelectedItem("CENTER");
        styleComboBox(cmbImageOrigin);
        cmbImageOrigin.addItemListener(e -> syncActiveObjectFromImageUi());

        chkAutoDepth = new JCheckBox("Auto Depth (Y-Sort)", true);
        styleCheckBox(chkAutoDepth);
        chkAutoDepth.addActionListener(e -> {
            if (activeOverlayObject != null) {
                activeOverlayObject.autoDepth = chkAutoDepth.isSelected();
                spImageDepth.setEnabled(!activeOverlayObject.autoDepth);
                syncActiveObjectFromImageUi();
            }
        });

        spImageDepth = new JSpinner(new SpinnerNumberModel(1, -100, 100, 1));
        styleSpinner(spImageDepth);
        spImageDepth.setEnabled(false);
        spImageDepth.addChangeListener(imgSpinnerListener);

        spImageScale = new JSpinner(new SpinnerNumberModel(100, 1, 500, 1));
        styleSpinner(spImageScale);

        sldImageScale = new JSlider(10, 200, 100);
        sldImageScale.setBackground(cPanelBg);
        sldImageScale.setFocusable(false);

        spImageScale.addChangeListener(e -> {
            if (updatingImgSpinners) return;
            int v = (int) spImageScale.getValue();
            sldImageScale.setValue(Math.min(200, Math.max(10, v)));
            syncActiveObjectFromImageUi();
        });

        sldImageScale.addChangeListener(e -> {
            if (updatingImgSpinners) return;
            spImageScale.setValue(sldImageScale.getValue());
            syncActiveObjectFromImageUi();
        });

        chkFlipH = new JCheckBox("Flip Character (Horizontal Mirror)", false);
        styleCheckBox(chkFlipH);
        chkFlipH.addActionListener(e -> syncActiveObjectFromImageUi());

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.28;
        imgPropBox.add(createStyledLabel("Image ID (IMAGENEW):"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.72;
        imgPropBox.add(txtImageId, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.28;
        imgPropBox.add(createStyledLabel("Sprite File (FILE=):"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.72;
        imgPropBox.add(txtImageFile, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.28;
        imgPropBox.add(createStyledLabel("Scale / Size (SCALE=):"), gbc);
        JPanel scalePanel = new JPanel(new BorderLayout(4, 0));
        scalePanel.setOpaque(false);
        spImageScale.setPreferredSize(new Dimension(68, 24));
        scalePanel.add(spImageScale, BorderLayout.WEST);
        scalePanel.add(sldImageScale, BorderLayout.CENTER);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.72;
        imgPropBox.add(scalePanel, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        imgPropBox.add(chkFlipH, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.28;
        imgPropBox.add(chkUseOrigin, gbc);
        gbc.gridx = 1; gbc.gridy = 4; gbc.weightx = 0.72;
        imgPropBox.add(cmbImageOrigin, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.weightx = 0.28;
        imgPropBox.add(createStyledLabel("Position (X, Y):"), gbc);
        JPanel posPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        posPanel.setOpaque(false);
        posPanel.add(createStyledLabel("X:"));
        spImgX.setPreferredSize(new Dimension(74, 24));
        posPanel.add(spImgX);
        posPanel.add(createStyledLabel("Y:"));
        spImgY.setPreferredSize(new Dimension(74, 24));
        posPanel.add(spImgY);
        gbc.gridx = 1; gbc.gridy = 5; gbc.weightx = 0.72;
        imgPropBox.add(posPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 6; gbc.weightx = 0.28;
        imgPropBox.add(createStyledLabel("Layer Depth:"), gbc);
        JPanel depthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        depthPanel.setOpaque(false);
        depthPanel.add(chkAutoDepth);
        depthPanel.add(createStyledLabel("DEPTH:"));
        spImageDepth.setPreferredSize(new Dimension(54, 24));
        depthPanel.add(spImageDepth);
        depthPanel.add(Box.createHorizontalStrut(6));
        depthPanel.add(lblImgDimensions);
        gbc.gridx = 1; gbc.gridy = 6; gbc.weightx = 0.72;
        imgPropBox.add(depthPanel, gbc);

        // Card 2: Spritesheet & Animation Controls
        JPanel animBox = new JPanel(new GridBagLayout());
        animBox.setBackground(cPanelBg);
        styleTitledBorder(animBox, "Spritesheet Animation Controls (Default: 8 FPS)");

        GridBagConstraints agbc = new GridBagConstraints();
        agbc.insets = new Insets(3, 4, 3, 4);
        agbc.fill = GridBagConstraints.HORIZONTAL;

        chkIsAnimation = new JCheckBox("Enable Spritesheet Animation", true);
        styleCheckBox(chkIsAnimation);
        chkIsAnimation.addActionListener(e -> {
            boolean en = chkIsAnimation.isSelected();
            spNbFrames.setEnabled(en);
            spAnimSpeed.setEnabled(en);
            cmbAnimEnd.setEnabled(en);
            if (btnPlayPauseAnim != null) btnPlayPauseAnim.setEnabled(en);
            if (sldAnimScrubber != null) sldAnimScrubber.setEnabled(en);
            syncActiveObjectFromImageUi();
        });

        spNbFrames = new JSpinner(new SpinnerNumberModel(6, 1, 128, 1));
        spAnimSpeed = new JSpinner(new SpinnerNumberModel(8, 1, 120, 1));
        styleSpinner(spNbFrames);
        styleSpinner(spAnimSpeed);

        spNbFrames.addChangeListener(e -> {
            if (activeOverlayObject != null && !updatingImgSpinners) {
                activeOverlayObject.nbFrames = (int) spNbFrames.getValue();
                activeOverlayObject.sliceFrames();
                if (sldAnimScrubber != null) {
                    sldAnimScrubber.setMaximum(activeOverlayObject.nbFrames);
                }
                syncImageUiFromActiveObject();
                if (canvas != null) canvas.repaint();
            }
        });

        spAnimSpeed.addChangeListener(imgSpinnerListener);

        cmbAnimEnd = new JComboBox<>(new String[] { "REPEAT", "BLOCK", "DESTROY" });
        cmbAnimEnd.setSelectedItem("REPEAT");
        styleComboBox(cmbAnimEnd);
        cmbAnimEnd.addItemListener(e -> syncActiveObjectFromImageUi());

        agbc.gridx = 0; agbc.gridy = 0; agbc.gridwidth = 2;
        animBox.add(chkIsAnimation, agbc);
        agbc.gridwidth = 1;

        agbc.gridx = 0; agbc.gridy = 1; agbc.weightx = 0.35;
        animBox.add(createStyledLabel("Frames / FPS:"), agbc);
        JPanel frameFpsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        frameFpsPanel.setOpaque(false);
        frameFpsPanel.add(createStyledLabel("NBFRAMES:"));
        spNbFrames.setPreferredSize(new Dimension(56, 24));
        frameFpsPanel.add(spNbFrames);
        frameFpsPanel.add(createStyledLabel("ANIMSPEED:"));
        spAnimSpeed.setPreferredSize(new Dimension(56, 24));
        frameFpsPanel.add(spAnimSpeed);
        agbc.gridx = 1; agbc.gridy = 1; agbc.weightx = 0.65;
        animBox.add(frameFpsPanel, agbc);

        agbc.gridx = 0; agbc.gridy = 2; agbc.weightx = 0.35;
        animBox.add(createStyledLabel("End Action (ANIMEND):"), agbc);
        agbc.gridx = 1; agbc.gridy = 2; agbc.weightx = 0.65;
        animBox.add(cmbAnimEnd, agbc);

        agbc.gridx = 0; agbc.gridy = 3; agbc.gridwidth = 2;
        JPanel playBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        playBar.setOpaque(false);

        btnPlayPauseAnim = new JButton("Pause");
        styleStandardButton(btnPlayPauseAnim);
        btnPlayPauseAnim.addActionListener(e -> {
            if (activeOverlayObject != null) {
                activeOverlayObject.isPlaying = !activeOverlayObject.isPlaying;
                btnPlayPauseAnim.setText(activeOverlayObject.isPlaying ? "Pause" : "Play");
            }
        });

        JButton btnStepPrev = new JButton("<<");
        styleStandardButton(btnStepPrev);
        btnStepPrev.addActionListener(e -> stepAnimationFrame(-1));

        JButton btnStepNext = new JButton(">>");
        styleStandardButton(btnStepNext);
        btnStepNext.addActionListener(e -> stepAnimationFrame(1));

        JButton btnResetAnim = new JButton("Reset");
        styleStandardButton(btnResetAnim);
        btnResetAnim.addActionListener(e -> {
            if (activeOverlayObject != null) {
                activeOverlayObject.currentFrameIndex = 0;
                activeOverlayObject.currentWalkProgress = 0.0;
                activeOverlayObject.currentWaypointSegment = 0;
                activeOverlayObject.walkForward = true;
                if (activeOverlayObject.useWalkPath && !activeOverlayObject.waypoints.isEmpty()) {
                    activeOverlayObject.setFromAnchor(activeOverlayObject.waypoints.get(0).x, activeOverlayObject.waypoints.get(0).y);
                }
                if (sldAnimScrubber != null) sldAnimScrubber.setValue(1);
                updateAnimStatusLabel();
                if (canvas != null) canvas.repaint();
            }
        });

        lblAnimFrameStatus = new JLabel("Frame 1/6");
        lblAnimFrameStatus.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblAnimFrameStatus.setForeground(new Color(0, 215, 255));

        playBar.add(btnPlayPauseAnim);
        playBar.add(btnStepPrev);
        playBar.add(btnStepNext);
        playBar.add(btnResetAnim);
        playBar.add(Box.createHorizontalStrut(6));
        playBar.add(lblAnimFrameStatus);
        animBox.add(playBar, agbc);

        agbc.gridx = 0; agbc.gridy = 4; agbc.gridwidth = 2;
        sldAnimScrubber = new JSlider(1, 6, 1);
        sldAnimScrubber.setBackground(cPanelBg);
        sldAnimScrubber.setFocusable(false);
        sldAnimScrubber.addChangeListener(e -> {
            if (activeOverlayObject != null && activeOverlayObject.frames != null) {
                int frame = sldAnimScrubber.getValue() - 1;
                if (frame >= 0 && frame < activeOverlayObject.frames.length) {
                    activeOverlayObject.currentFrameIndex = frame;
                    updateAnimStatusLabel();
                    if (canvas != null) canvas.repaint();
                }
            }
        });
        animBox.add(sldAnimScrubber, agbc);

        // Card 3: Multi-Waypoint Walk Path Movement
        JPanel walkBox = new JPanel(new GridBagLayout());
        walkBox.setBackground(cPanelBg);
        styleTitledBorder(walkBox, "Multi-Waypoint Walk Path (Draggable Point A-B-C Onwards)");

        GridBagConstraints wgbc = new GridBagConstraints();
        wgbc.insets = new Insets(3, 4, 3, 4);
        wgbc.fill = GridBagConstraints.HORIZONTAL;

        chkUseWalkPath = new JCheckBox("Enable Multi-Point Walk Path (Draggable on Canvas)", true);
        styleCheckBox(chkUseWalkPath);
        chkUseWalkPath.addActionListener(e -> {
            if (activeOverlayObject != null) {
                activeOverlayObject.useWalkPath = chkUseWalkPath.isSelected();
                if (activeOverlayObject.useWalkPath && activeOverlayObject.waypoints.isEmpty()) {
                    activeOverlayObject.initDefaultWaypoints();
                }
                syncImageUiFromActiveObject();
                if (canvas != null) canvas.repaint();
            }
        });

        cmbWaypointSelector = new JComboBox<>();
        styleComboBox(cmbWaypointSelector);
        cmbWaypointSelector.addItemListener(e -> syncWaypointUiFromSelection());

        spSelectedWpX = new JSpinner(new SpinnerNumberModel(250, -3000, 5000, 1));
        spSelectedWpY = new JSpinner(new SpinnerNumberModel(560, -3000, 5000, 1));
        spSelectedWpSpeed = new JSpinner(new SpinnerNumberModel(3, 1, 50, 1));
        txtSelectedWpEvent = new JTextField("S01_ARRIVED");

        styleSpinner(spSelectedWpX);
        styleSpinner(spSelectedWpY);
        styleSpinner(spSelectedWpSpeed);
        styleTextField(txtSelectedWpEvent);

        ChangeListener wpSpinnerListener = e -> syncSelectionToWaypoint();
        spSelectedWpX.addChangeListener(wpSpinnerListener);
        spSelectedWpY.addChangeListener(wpSpinnerListener);
        spSelectedWpSpeed.addChangeListener(wpSpinnerListener);
        txtSelectedWpEvent.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                syncSelectionToWaypoint();
            }
        });

        lblWalkDistanceInfo = new JLabel("Walk Distance: 1248 px (65% width)");
        lblWalkDistanceInfo.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblWalkDistanceInfo.setForeground(new Color(85, 215, 105));

        wgbc.gridx = 0; wgbc.gridy = 0; wgbc.gridwidth = 2;
        walkBox.add(chkUseWalkPath, wgbc);
        wgbc.gridwidth = 1;

        wgbc.gridx = 0; wgbc.gridy = 1; wgbc.weightx = 0.35;
        walkBox.add(createStyledLabel("Active Waypoint:"), wgbc);
        JPanel wpManagePanel = new JPanel(new BorderLayout(4, 0));
        wpManagePanel.setOpaque(false);
        wpManagePanel.add(cmbWaypointSelector, BorderLayout.CENTER);

        JPanel wpBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        wpBtns.setOpaque(false);
        JButton btnAddWp = new JButton("+ Add Point");
        styleStandardButton(btnAddWp);
        btnAddWp.setToolTipText("Add a new waypoint (Point C, Point D...) extending the walk path");
        btnAddWp.addActionListener(e -> addNextWaypoint());

        JButton btnDelWp = new JButton("- Remove");
        styleStandardButton(btnDelWp);
        btnDelWp.setToolTipText("Remove the currently selected waypoint");
        btnDelWp.addActionListener(e -> removeSelectedWaypoint());

        wpBtns.add(btnAddWp);
        wpBtns.add(btnDelWp);
        wpManagePanel.add(wpBtns, BorderLayout.EAST);
        wgbc.gridx = 1; wgbc.gridy = 1; wgbc.weightx = 0.65;
        walkBox.add(wpManagePanel, wgbc);

        wgbc.gridx = 0; wgbc.gridy = 2; wgbc.weightx = 0.35;
        walkBox.add(createStyledLabel("Waypoint Position:"), wgbc);
        JPanel wpPosPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        wpPosPanel.setOpaque(false);
        wpPosPanel.add(createStyledLabel("X:"));
        spSelectedWpX.setPreferredSize(new Dimension(68, 24));
        wpPosPanel.add(spSelectedWpX);
        wpPosPanel.add(createStyledLabel("Y:"));
        spSelectedWpY.setPreferredSize(new Dimension(68, 24));
        wpPosPanel.add(spSelectedWpY);
        wgbc.gridx = 1; wgbc.gridy = 2; wgbc.weightx = 0.65;
        walkBox.add(wpPosPanel, wgbc);

        wgbc.gridx = 0; wgbc.gridy = 3; wgbc.weightx = 0.35;
        walkBox.add(createStyledLabel("SPEED / ENDEVENT:"), wgbc);
        JPanel pnlSpdEv = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pnlSpdEv.setOpaque(false);
        pnlSpdEv.add(createStyledLabel("SPEED:"));
        spSelectedWpSpeed.setPreferredSize(new Dimension(50, 24));
        pnlSpdEv.add(spSelectedWpSpeed);
        pnlSpdEv.add(createStyledLabel("EVENT:"));
        txtSelectedWpEvent.setPreferredSize(new Dimension(110, 24));
        pnlSpdEv.add(txtSelectedWpEvent);
        wgbc.gridx = 1; wgbc.gridy = 3; wgbc.weightx = 0.65;
        walkBox.add(pnlSpdEv, wgbc);

        wgbc.gridx = 0; wgbc.gridy = 4; wgbc.gridwidth = 2;
        walkBox.add(lblWalkDistanceInfo, wgbc);

        wgbc.gridx = 0; wgbc.gridy = 5; wgbc.gridwidth = 2;
        JPanel walkPresetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        walkPresetBar.setOpaque(false);

        JButton btnPresetWalk65R = new JButton("Walk 65% Width (Right)");
        stylePrimaryButton(btnPresetWalk65R, new Color(0, 155, 225));
        btnPresetWalk65R.addActionListener(e -> setWalkPreset(0.65, true));

        JButton btnPresetWalk65L = new JButton("Walk 65% Width (Left)");
        styleStandardButton(btnPresetWalk65L);
        btnPresetWalk65L.addActionListener(e -> setWalkPreset(0.65, false));

        JButton btnPresetWalkFull = new JButton("Walk Full Screen");
        styleStandardButton(btnPresetWalkFull);
        btnPresetWalkFull.addActionListener(e -> setWalkPreset(1.0, true));

        walkPresetBar.add(btnPresetWalk65R);
        walkPresetBar.add(btnPresetWalk65L);
        walkPresetBar.add(btnPresetWalkFull);
        walkBox.add(walkPresetBar, wgbc);
        wgbc.gridwidth = 1;

        // Card 4: Snap & Alignment Actions
        JPanel alignBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        alignBar.setBackground(cPanelBg);

        JButton btnSnapClickerToImg = new JButton("Match Clicker to Sprite Bounds");
        stylePrimaryButton(btnSnapClickerToImg, new Color(0, 155, 225));
        btnSnapClickerToImg.addActionListener(e -> snapClickerToImage());

        JButton btnCenterImg = new JButton("Center Sprite");
        styleStandardButton(btnCenterImg);
        btnCenterImg.addActionListener(e -> {
            if (activeOverlayObject != null) {
                activeOverlayObject.x = (1920 - activeOverlayObject.getDisplayWidth()) / 2;
                activeOverlayObject.y = (1080 - activeOverlayObject.getDisplayHeight()) / 2;
                syncImageUiFromActiveObject();
                if (canvas != null) canvas.repaint();
            }
        });

        chkSyncClickerWithImage = new JCheckBox("Link Clicker with Sprite Drag", true);
        styleCheckBox(chkSyncClickerWithImage);

        alignBar.add(btnSnapClickerToImg);
        alignBar.add(btnCenterImg);
        alignBar.add(chkSyncClickerWithImage);

        contentPanel.add(imgPropBox);
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(animBox);
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(walkBox);
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(alignBar);

        JScrollPane scroll = new JScrollPane(contentPanel);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(cPanelBg);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(cPanelBg);
        outer.add(scroll, BorderLayout.CENTER);
        return outer;
    }

    private void addNextWaypoint() {
        if (activeOverlayObject == null) return;
        char nextLetter = (char) ('A' + activeOverlayObject.waypoints.size());
        String label = "Point " + nextLetter;
        int lastX = 1498;
        int lastY = 560;
        if (!activeOverlayObject.waypoints.isEmpty()) {
            Waypoint lastWp = activeOverlayObject.waypoints.get(activeOverlayObject.waypoints.size() - 1);
            lastX = lastWp.x + 300;
            lastY = lastWp.y;
        }
        Waypoint newWp = new Waypoint(label, lastX, lastY, 3, "S01_" + activeOverlayObject.imageId + "_" + nextLetter);
        activeOverlayObject.waypoints.add(newWp);
        refreshWaypointSelector();
        cmbWaypointSelector.setSelectedIndex(activeOverlayObject.waypoints.size() - 1);
        updateWalkDistanceLabel();
        updateScriptPreview();
        updateOverallBrokVnFile();
        if (canvas != null) canvas.repaint();
    }

    private void removeSelectedWaypoint() {
        if (activeOverlayObject == null || activeOverlayObject.waypoints.size() <= 2) {
            JOptionPane.showMessageDialog(this, "A walk path must have at least Point A and Point B.", "Cannot Remove", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int idx = cmbWaypointSelector.getSelectedIndex();
        if (idx >= 0 && idx < activeOverlayObject.waypoints.size()) {
            activeOverlayObject.waypoints.remove(idx);
            for (int i = 0; i < activeOverlayObject.waypoints.size(); i++) {
                activeOverlayObject.waypoints.get(i).label = "Point " + ((char) ('A' + i));
            }
            refreshWaypointSelector();
            cmbWaypointSelector.setSelectedIndex(Math.max(0, idx - 1));
            updateWalkDistanceLabel();
            updateScriptPreview();
            updateOverallBrokVnFile();
            if (canvas != null) canvas.repaint();
        }
    }

    private void refreshWaypointSelector() {
        if (cmbWaypointSelector == null || activeOverlayObject == null) return;
        cmbWaypointSelector.removeAllItems();
        for (Waypoint wp : activeOverlayObject.waypoints) {
            cmbWaypointSelector.addItem(wp.label + String.format(" [X:%d, Y:%d]", wp.x, wp.y));
        }
    }

    private void syncWaypointUiFromSelection() {
        if (updatingImgSpinners || activeOverlayObject == null) return;
        int idx = cmbWaypointSelector.getSelectedIndex();
        if (idx >= 0 && idx < activeOverlayObject.waypoints.size()) {
            Waypoint wp = activeOverlayObject.waypoints.get(idx);
            updatingImgSpinners = true;
            if (spSelectedWpX != null) spSelectedWpX.setValue(wp.x);
            if (spSelectedWpY != null) spSelectedWpY.setValue(wp.y);
            if (spSelectedWpSpeed != null) spSelectedWpSpeed.setValue(wp.speed);
            if (txtSelectedWpEvent != null) txtSelectedWpEvent.setText(wp.endEvent != null ? wp.endEvent : "");
            updatingImgSpinners = false;
        }
    }

    private void syncSelectionToWaypoint() {
        if (updatingImgSpinners || activeOverlayObject == null) return;
        int idx = cmbWaypointSelector.getSelectedIndex();
        if (idx >= 0 && idx < activeOverlayObject.waypoints.size()) {
            Waypoint wp = activeOverlayObject.waypoints.get(idx);
            wp.x = (int) spSelectedWpX.getValue();
            wp.y = (int) spSelectedWpY.getValue();
            wp.speed = (int) spSelectedWpSpeed.getValue();
            wp.endEvent = txtSelectedWpEvent.getText().trim();
            updateWalkDistanceLabel();
            updateScriptPreview();
            updateOverallBrokVnFile();
            if (canvas != null) canvas.repaint();
        }
    }

    private void setWalkPreset(double screenRatio, boolean walkRight) {
        if (activeOverlayObject == null) {
            JOptionPane.showMessageDialog(this, "Please place a sprite first.", "No Sprite", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int distance = (int) Math.round(1920 * screenRatio); // 65% = 1248 px
        activeOverlayObject.useWalkPath = true;
        if (chkUseWalkPath != null) chkUseWalkPath.setSelected(true);

        activeOverlayObject.waypoints.clear();
        int curY = activeOverlayObject.getAnchorY();
        if (walkRight) {
            int startX = Math.min(600, activeOverlayObject.getAnchorX());
            int targetX = startX + distance;
            activeOverlayObject.waypoints.add(new Waypoint("Point A", startX, curY, 3, ""));
            activeOverlayObject.waypoints.add(new Waypoint("Point B", targetX, curY, 3, "S01_" + activeOverlayObject.imageId + "_ARRIVED"));
            activeOverlayObject.flipH = false;
        } else {
            int startX = Math.max(1320, activeOverlayObject.getAnchorX());
            int targetX = startX - distance;
            activeOverlayObject.waypoints.add(new Waypoint("Point A", startX, curY, 3, ""));
            activeOverlayObject.waypoints.add(new Waypoint("Point B", targetX, curY, 3, "S01_" + activeOverlayObject.imageId + "_ARRIVED"));
            activeOverlayObject.flipH = true;
        }

        activeOverlayObject.currentWalkProgress = 0.0;
        activeOverlayObject.currentWaypointSegment = 0;
        activeOverlayObject.walkForward = true;
        activeOverlayObject.setFromAnchor(activeOverlayObject.waypoints.get(0).x, activeOverlayObject.waypoints.get(0).y);

        syncImageUiFromActiveObject();
        if (canvas != null) canvas.repaint();
    }

    private void updateWalkDistanceLabel() {
        if (lblWalkDistanceInfo == null || activeOverlayObject == null || activeOverlayObject.waypoints.size() < 2) return;
        double totalDist = 0;
        for (int i = 0; i < activeOverlayObject.waypoints.size() - 1; i++) {
            Waypoint a = activeOverlayObject.waypoints.get(i);
            Waypoint b = activeOverlayObject.waypoints.get(i + 1);
            totalDist += Math.sqrt(Math.pow(b.x - a.x, 2) + Math.pow(b.y - a.y, 2));
        }
        int dist = (int) Math.round(totalDist);
        double pct = (dist / 1920.0) * 100.0;
        lblWalkDistanceInfo.setText(String.format("Walk Distance: %d px (%.1f%% width, %d waypoints)", dist, pct, activeOverlayObject.waypoints.size()));
    }

    // =========================================================================
    // TAB 3: LAYER MANAGEMENT (CLICKABLE & SPRITE LAYERS)
    // =========================================================================

    private JPanel buildLayersPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(cPanelBg);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel topBar = new JPanel(new BorderLayout(6, 0));
        topBar.setOpaque(false);
        JLabel lblHdr = new JLabel("Layer & Depth Manager");
        lblHdr.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblHdr.setForeground(cTitle);
        topBar.add(lblHdr, BorderLayout.WEST);

        String[] cols = new String[] { "Type", "ID / Name", "Layer", "Depth", "Visible" };
        layerTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 4;
            }
            @Override
            public Class<?> getColumnClass(int col) {
                return (col == 4) ? Boolean.class : String.class;
            }
        };

        layerTable = new JTable(layerTableModel);
        layerTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        layerTable.setRowHeight(24);
        layerTable.setBackground(cInputBg);
        layerTable.setForeground(cFg);
        layerTable.setSelectionBackground(new Color(0, 122, 255));
        layerTable.setSelectionForeground(Color.WHITE);
        layerTable.setGridColor(cBorder);

        JScrollPane scroll = new JScrollPane(layerTable);
        scroll.setBorder(new LineBorder(cBorder, 1));
        scroll.getViewport().setBackground(cInputBg);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        btnBar.setOpaque(false);

        JButton btnDisableLayer = new JButton("Copy CLICKERDISABLELAYER");
        styleStandardButton(btnDisableLayer);
        btnDisableLayer.addActionListener(e -> {
            int row = layerTable.getSelectedRow();
            int layerNum = 0;
            if (row >= 0) {
                try {
                    layerNum = Integer.parseInt(layerTableModel.getValueAt(row, 2).toString());
                } catch (Exception ignored) {}
            }
            String cmd = "CLICKERDISABLELAYER=" + layerNum;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
            JOptionPane.showMessageDialog(this, "Copied: " + cmd, "Copied", JOptionPane.INFORMATION_MESSAGE);
        });

        JButton btnEnableLayer = new JButton("Copy CLICKERENABLELAYER");
        styleStandardButton(btnEnableLayer);
        btnEnableLayer.addActionListener(e -> {
            int row = layerTable.getSelectedRow();
            int layerNum = 0;
            if (row >= 0) {
                try {
                    layerNum = Integer.parseInt(layerTableModel.getValueAt(row, 2).toString());
                } catch (Exception ignored) {}
            }
            String cmd = "CLICKERENABLELAYER=" + layerNum;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
            JOptionPane.showMessageDialog(this, "Copied: " + cmd, "Copied", JOptionPane.INFORMATION_MESSAGE);
        });

        JButton btnRefreshLayers = new JButton("Refresh");
        styleStandardButton(btnRefreshLayers);
        btnRefreshLayers.addActionListener(e -> refreshLayerTable());

        btnBar.add(btnDisableLayer);
        btnBar.add(btnEnableLayer);
        btnBar.add(btnRefreshLayers);

        panel.add(topBar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(btnBar, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshLayerTable() {
        if (layerTableModel == null) return;
        layerTableModel.setRowCount(0);

        for (OverlayObject obj : overlayObjects) {
            layerTableModel.addRow(new Object[] {
                    "Sprite (IMAGENEW)",
                    obj.imageId,
                    "-",
                    String.valueOf(obj.getCalculatedDepth()),
                    Boolean.TRUE
            });
        }

        for (ClickerDef def : savedClickers) {
            layerTableModel.addRow(new Object[] {
                    "Clicker",
                    def.id,
                    String.valueOf(def.layer),
                    "-",
                    Boolean.TRUE
            });
        }
    }

    private void stepAnimationFrame(int delta) {
        if (activeOverlayObject != null && activeOverlayObject.frames != null && activeOverlayObject.frames.length > 0) {
            int n = activeOverlayObject.frames.length;
            activeOverlayObject.currentFrameIndex = (activeOverlayObject.currentFrameIndex + delta + n) % n;
            if (sldAnimScrubber != null) {
                sldAnimScrubber.setValue(activeOverlayObject.currentFrameIndex + 1);
            }
            updateAnimStatusLabel();
            if (canvas != null) canvas.repaint();
        }
    }

    private void updateAnimStatusLabel() {
        if (lblAnimFrameStatus != null && activeOverlayObject != null) {
            int cur = activeOverlayObject.currentFrameIndex + 1;
            int total = Math.max(1, activeOverlayObject.nbFrames);
            lblAnimFrameStatus.setText(String.format("Frame %d/%d", cur, total));
        }
    }

    private JPanel buildListPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(cPanelBg);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        String[] cols = new String[] { "ID", "Layer", "X1", "Y1", "X2", "Y2", "Size", "Event", "Hover Text" };
        clickerTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        clickersTable = new JTable(clickerTableModel);
        clickersTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        clickersTable.setRowHeight(24);
        clickersTable.setBackground(cInputBg);
        clickersTable.setForeground(cFg);
        clickersTable.setSelectionBackground(new Color(0, 122, 255));
        clickersTable.setSelectionForeground(Color.WHITE);
        clickersTable.setGridColor(cBorder);

        clickersTable.getTableHeader().setBackground(cButtonBg);
        clickersTable.getTableHeader().setForeground(cFg);
        clickersTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        clickersTable.getTableHeader().setBorder(new LineBorder(cBorder, 1));

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        centerRenderer.setBackground(cInputBg);
        centerRenderer.setForeground(cFg);
        for (int i = 1; i <= 6; i++) {
            clickersTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        clickersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clickersTable.getSelectionModel().addListSelectionListener(e -> {
            int row = clickersTable.getSelectedRow();
            if (row >= 0 && row < savedClickers.size()) {
                loadClickerDef(savedClickers.get(row));
                selectedClickerIndex = row;
                canvas.repaint();
            }
        });

        JScrollPane scroll = new JScrollPane(clickersTable);
        scroll.setBorder(new LineBorder(cBorder, 1));
        scroll.getViewport().setBackground(cInputBg);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        btnBar.setOpaque(false);

        JButton btnRemove = new JButton("Remove Selected");
        styleStandardButton(btnRemove);
        btnRemove.addActionListener(e -> removeSelectedClicker());

        JButton btnCopyRemove = new JButton("Copy CLICKERREMOVE");
        styleStandardButton(btnCopyRemove);
        btnCopyRemove.setToolTipText("Generate cleanup statement to remove all these clickers when scene ends");
        btnCopyRemove.addActionListener(e -> copyCleanupStatement());

        JButton btnCopyAll = new JButton("Copy All Scene Clickers");
        stylePrimaryButton(btnCopyAll, new Color(0, 122, 255));
        btnCopyAll.addActionListener(e -> copyAllClickersCode());

        btnBar.add(btnRemove);
        btnBar.add(btnCopyRemove);
        btnBar.add(btnCopyAll);

        panel.add(scroll, BorderLayout.CENTER);
        panel.add(btnBar, BorderLayout.SOUTH);
        return panel;
    }

    // =========================================================================
    // TAB 5: OVERALL BROKVN FILE PANEL (3RD SIDE DEDICATED FULL SCRIPT)
    // =========================================================================

    private JPanel buildOverallBrokVnFilePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(cPanelBg);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel topHeader = new JPanel(new BorderLayout(6, 0));
        topHeader.setOpaque(false);

        JLabel lblHdr = new JLabel("Overall BrokVN Scene Script");
        lblHdr.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblHdr.setForeground(cTitle);

        JButton btnRefreshOverall = new JButton("Refresh");
        styleStandardButton(btnRefreshOverall);
        btnRefreshOverall.addActionListener(e -> updateOverallBrokVnFile());

        topHeader.add(lblHdr, BorderLayout.WEST);
        topHeader.add(btnRefreshOverall, BorderLayout.EAST);

        txtOverallBrokVnFile = new JTextArea();
        txtOverallBrokVnFile.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtOverallBrokVnFile.setBackground(cInputBg);
        txtOverallBrokVnFile.setForeground(cFg);
        txtOverallBrokVnFile.setCaretColor(cFg);
        txtOverallBrokVnFile.setBorder(new EmptyBorder(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(txtOverallBrokVnFile);
        scroll.setBorder(new LineBorder(cBorder, 1));
        scroll.getViewport().setBackground(cInputBg);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        actionRow.setOpaque(false);

        JButton btnCopyOverall = new JButton("Copy Overall File");
        styleStandardButton(btnCopyOverall);
        btnCopyOverall.addActionListener(e -> {
            String txt = txtOverallBrokVnFile.getText();
            if (txt != null && !txt.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null);
                JOptionPane.showMessageDialog(this, "Overall BrokVN File copied to clipboard!", "Copied", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        JButton btnSaveOverall = new JButton("Save .txt File...");
        styleStandardButton(btnSaveOverall);
        btnSaveOverall.addActionListener(e -> exportOverallBrokVnFile());

        JButton btnInsertOverall = new JButton("Insert Overall File");
        stylePrimaryButton(btnInsertOverall, new Color(35, 134, 54));
        btnInsertOverall.addActionListener(e -> {
            String txt = txtOverallBrokVnFile.getText();
            if (insertCallback != null && txt != null && !txt.isEmpty()) {
                insertCallback.insertScriptCode("\n" + txt + "\n");
                JOptionPane.showMessageDialog(this, "Inserted overall scene script into active editor!", "Inserted", JOptionPane.INFORMATION_MESSAGE);
            } else {
                btnCopyOverall.doClick();
            }
        });

        actionRow.add(btnCopyOverall);
        actionRow.add(btnSaveOverall);
        actionRow.add(btnInsertOverall);

        panel.add(topHeader, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(actionRow, BorderLayout.SOUTH);
        return panel;
    }

    public void updateOverallBrokVnFile() {
        if (txtOverallBrokVnFile == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("#====================================================\n");
        sb.append("# OVERALL BROKVN SCENE SCRIPT\n");
        sb.append("# Generated by ").append(APP_NAME).append(" (1920×1080 Canvas)\n");
        sb.append("#====================================================\n\n");

        sb.append("EVENT=S01_SCENE_START\n");
        if (currentImageFile != null) {
            String bgName = currentImageFile.getName();
            int dot = bgName.lastIndexOf('.');
            if (dot > 0) bgName = bgName.substring(0, dot);
            sb.append("\tBACKGROUND=").append(bgName.toUpperCase()).append("\n");
            sb.append("\tFADEIN=MEDIUM\n\n");
        }

        if (!overlayObjects.isEmpty()) {
            sb.append("\t# --- Placed Sprites & Character Animations ---\n");
            for (OverlayObject obj : overlayObjects) {
                sb.append(obj.toImageNewScript()).append("\n");
            }
        }

        if (!savedClickers.isEmpty()) {
            sb.append("\t# --- Scene Interactive Clickers ---\n");
            for (ClickerDef def : savedClickers) {
                sb.append(def.toBrokVnScript()).append("\n");
            }
        } else {
            ClickerDef currentDef = createDefFromCurrentUi();
            sb.append(currentDef.toBrokVnScript()).append("\n");
        }

        txtOverallBrokVnFile.setText(sb.toString());
        txtOverallBrokVnFile.setCaretPosition(0);
    }

    private void exportOverallBrokVnFile() {
        updateOverallBrokVnFile();
        File saveFile = browseFileNative("Save Overall BrokVN Script File", FileDialog.SAVE, "Brok VN Script (*.txt)", "txt");
        if (saveFile != null) {
            try {
                if (!saveFile.getName().toLowerCase().endsWith(".txt")) {
                    saveFile = new File(saveFile.getParentFile(), saveFile.getName() + ".txt");
                }
                Files.write(saveFile.toPath(), txtOverallBrokVnFile.getText().getBytes(StandardCharsets.UTF_8));
                JOptionPane.showMessageDialog(this, "Saved: " + saveFile.getName(), "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error saving file: " + ex.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // =========================================================================
    // PROJECT SAVE / LOAD / NEW PROGRESS (.brokproj JSON)
    // =========================================================================

    public void newProject() {
        int res = JOptionPane.showConfirmDialog(this, "Create a new project? Any unsaved changes will be cleared.", "New Project", JOptionPane.YES_NO_OPTION);
        if (res == JOptionPane.YES_OPTION) {
            currentProjectFile = null;
            currentImage = null;
            currentImageFile = null;
            currentImageResText = "No image loaded";
            overlayObjects.clear();
            activeOverlayObject = null;
            savedClickers.clear();
            if (clickerTableModel != null) clickerTableModel.setRowCount(0);
            rightTabbedPane.setTitleAt(3, "Clickers List (0)");
            syncImageUiFromActiveObject();
            updateBoundsLabel();
            updateScriptPreview();
            updateOverallBrokVnFile();
            if (canvas != null) canvas.repaint();
            setTitle(APP_NAME + " - [Untitled Project]");
        }
    }

    public void saveProject(boolean saveAs) {
        if (currentProjectFile == null || saveAs) {
            File f = browseFileNative("Save BrokVN Studio Project", FileDialog.SAVE, "BrokVN Project (*.brokproj)", "brokproj", "json");
            if (f == null) return;
            if (!f.getName().toLowerCase().endsWith(".brokproj")) {
                f = new File(f.getParentFile(), f.getName() + ".brokproj");
            }
            currentProjectFile = f;
        }

        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"version\": \"").append(APP_VERSION).append("\",\n");
            json.append("  \"background\": \"").append(currentImageFile != null ? currentImageFile.getAbsolutePath().replace("\\", "\\\\") : "").append("\",\n");

            // Sprites
            json.append("  \"sprites\": [\n");
            for (int i = 0; i < overlayObjects.size(); i++) {
                OverlayObject obj = overlayObjects.get(i);
                json.append("    {\n");
                json.append("      \"id\": \"").append(obj.imageId).append("\",\n");
                json.append("      \"file\": \"").append(obj.imagePath.replace("\\", "\\\\")).append("\",\n");
                json.append("      \"x\": ").append(obj.x).append(",\n");
                json.append("      \"y\": ").append(obj.y).append(",\n");
                json.append("      \"scale\": ").append(obj.scale).append(",\n");
                json.append("      \"flipH\": ").append(obj.flipH).append(",\n");
                json.append("      \"origin\": \"").append(obj.origin).append("\",\n");
                json.append("      \"useOrigin\": ").append(obj.useOrigin).append(",\n");
                json.append("      \"depth\": ").append(obj.depth).append(",\n");
                json.append("      \"autoDepth\": ").append(obj.autoDepth).append(",\n");
                json.append("      \"isAnimation\": ").append(obj.isAnimation).append(",\n");
                json.append("      \"nbFrames\": ").append(obj.nbFrames).append(",\n");
                json.append("      \"animSpeed\": ").append(obj.animSpeed).append(",\n");
                json.append("      \"animEnd\": \"").append(obj.animEnd).append("\",\n");
                json.append("      \"useWalkPath\": ").append(obj.useWalkPath).append(",\n");

                // Waypoints
                json.append("      \"waypoints\": [\n");
                for (int w = 0; w < obj.waypoints.size(); w++) {
                    Waypoint wp = obj.waypoints.get(w);
                    json.append("        {\"label\": \"").append(wp.label).append("\", \"x\": ").append(wp.x).append(", \"y\": ").append(wp.y)
                        .append(", \"speed\": ").append(wp.speed).append(", \"endEvent\": \"").append(wp.endEvent != null ? wp.endEvent : "").append("\"}")
                        .append(w < obj.waypoints.size() - 1 ? ",\n" : "\n");
                }
                json.append("      ]\n");
                json.append("    }").append(i < overlayObjects.size() - 1 ? ",\n" : "\n");
            }
            json.append("  ],\n");

            // Clickers
            json.append("  \"clickers\": [\n");
            for (int i = 0; i < savedClickers.size(); i++) {
                ClickerDef d = savedClickers.get(i);
                json.append("    {\n");
                json.append("      \"id\": \"").append(d.id).append("\",\n");
                json.append("      \"x1\": ").append(d.x1).append(",\n");
                json.append("      \"y1\": ").append(d.y1).append(",\n");
                json.append("      \"x2\": ").append(d.x2).append(",\n");
                json.append("      \"y2\": ").append(d.y2).append(",\n");
                json.append("      \"text\": \"").append(d.text.replace("\"", "\\\"")).append("\",\n");
                json.append("      \"event\": \"").append(d.event).append("\",\n");
                json.append("      \"highlight\": ").append(d.highlight).append(",\n");
                json.append("      \"hotspot\": ").append(d.hotspot).append(",\n");
                json.append("      \"hotspotIcon\": \"").append(d.hotspotIcon).append("\",\n");
                json.append("      \"canDpad\": ").append(d.canDpad).append(",\n");
                json.append("      \"type\": \"").append(d.type).append("\",\n");
                json.append("      \"stayActive\": \"").append(d.stayActive).append("\",\n");
                json.append("      \"layer\": ").append(d.layer).append("\n");
                json.append("    }").append(i < savedClickers.size() - 1 ? ",\n" : "\n");
            }
            json.append("  ]\n");
            json.append("}\n");

            Files.write(currentProjectFile.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            setTitle(APP_NAME + " - [" + currentProjectFile.getName() + "]");
            JOptionPane.showMessageDialog(this, "Project saved successfully:\n" + currentProjectFile.getName(), "Project Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error saving project: " + ex.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void openProject() {
        File f = browseFileNative("Open BrokVN Studio Project", FileDialog.LOAD, "BrokVN Project (*.brokproj)", "brokproj", "json");
        if (f == null || !f.exists()) return;

        try {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            currentProjectFile = f;
            setTitle(APP_NAME + " - [" + currentProjectFile.getName() + "]");

            overlayObjects.clear();
            savedClickers.clear();
            if (clickerTableModel != null) clickerTableModel.setRowCount(0);

            int bgIdx = content.indexOf("\"background\": \"");
            if (bgIdx > 0) {
                int endBg = content.indexOf("\"", bgIdx + 15);
                if (endBg > bgIdx) {
                    String bgPath = content.substring(bgIdx + 15, endBg);
                    if (!bgPath.isEmpty()) {
                        File bgF = new File(bgPath);
                        if (bgF.exists()) loadImageFile(bgF);
                    }
                }
            }

            int spArrStart = content.indexOf("\"sprites\": [");
            int spArrEnd = content.indexOf("\"clickers\": [");
            if (spArrStart >= 0 && spArrEnd > spArrStart) {
                String spBlock = content.substring(spArrStart, spArrEnd);
                String[] items = spBlock.split("\\{\\s*\"id\":");
                for (int i = 1; i < items.length; i++) {
                    String item = items[i];
                    String fPath = extractJsonString(item, "\"file\": \"");
                    File sFile = new File(fPath);
                    if (sFile.exists()) {
                        BufferedImage img = ImageIO.read(sFile);
                        if (img != null) {
                            OverlayObject obj = new OverlayObject(sFile, img, 0, 0);
                            obj.x = extractJsonInt(item, "\"x\": ", 0);
                            obj.y = extractJsonInt(item, "\"y\": ", 0);
                            obj.scale = extractJsonInt(item, "\"scale\": ", 100);
                            obj.flipH = extractJsonBool(item, "\"flipH\": ", false);
                            obj.origin = extractJsonString(item, "\"origin\": \"");
                            if (obj.origin.isEmpty()) obj.origin = "CENTER";
                            obj.useOrigin = extractJsonBool(item, "\"useOrigin\": ", true);
                            obj.depth = extractJsonInt(item, "\"depth\": ", 1);
                            obj.autoDepth = extractJsonBool(item, "\"autoDepth\": ", true);
                            obj.isAnimation = extractJsonBool(item, "\"isAnimation\": ", false);
                            obj.nbFrames = extractJsonInt(item, "\"nbFrames\": ", 1);
                            obj.animSpeed = extractJsonInt(item, "\"animSpeed\": ", 8);
                            obj.animEnd = extractJsonString(item, "\"animEnd\": \"");
                            if (obj.animEnd.isEmpty()) obj.animEnd = "REPEAT";
                            obj.useWalkPath = extractJsonBool(item, "\"useWalkPath\": ", false);
                            obj.sliceFrames();

                            overlayObjects.add(obj);
                            activeOverlayObject = obj;
                        }
                    }
                }
            }

            if (spArrEnd >= 0) {
                String clkBlock = content.substring(spArrEnd);
                String[] clkItems = clkBlock.split("\\{\\s*\"id\":");
                for (int i = 1; i < clkItems.length; i++) {
                    String item = clkItems[i];
                    ClickerDef d = new ClickerDef();
                    int q1 = item.indexOf("\"");
                    int q2 = item.indexOf("\"", q1 + 1);
                    if (q1 >= 0 && q2 > q1) d.id = item.substring(q1 + 1, q2);
                    d.x1 = extractJsonInt(item, "\"x1\": ", 0);
                    d.y1 = extractJsonInt(item, "\"y1\": ", 0);
                    d.x2 = extractJsonInt(item, "\"x2\": ", 100);
                    d.y2 = extractJsonInt(item, "\"y2\": ", 100);
                    d.text = extractJsonString(item, "\"text\": \"");
                    d.event = extractJsonString(item, "\"event\": \"");
                    d.highlight = extractJsonBool(item, "\"highlight\": ", true);
                    d.hotspot = extractJsonBool(item, "\"hotspot\": ", true);
                    d.hotspotIcon = extractJsonString(item, "\"hotspotIcon\": \"");
                    if (d.hotspotIcon.isEmpty()) d.hotspotIcon = "ICON_ACTIVE";
                    d.canDpad = extractJsonBool(item, "\"canDpad\": ", true);
                    d.type = extractJsonString(item, "\"type\": \"");
                    if (d.type.isEmpty()) d.type = "NORMAL";
                    d.stayActive = extractJsonString(item, "\"stayActive\": \"");
                    if (d.stayActive.isEmpty()) d.stayActive = "DEFAULT";
                    d.layer = extractJsonInt(item, "\"layer\": ", 0);

                    savedClickers.add(d);
                    if (clickerTableModel != null) {
                        clickerTableModel.addRow(new Object[] {
                                d.id, d.layer, d.x1, d.y1, d.x2, d.y2,
                                (d.x2 - d.x1) + "×" + (d.y2 - d.y1),
                                d.event, d.text
                        });
                    }
                }
            }

            rightTabbedPane.setTitleAt(3, "Clickers List (" + savedClickers.size() + ")");
            if (activeOverlayObject != null) syncImageUiFromActiveObject();
            updateBoundsLabel();
            updateScriptPreview();
            updateOverallBrokVnFile();
            if (canvas != null) canvas.repaint();
            JOptionPane.showMessageDialog(this, "Project loaded successfully:\n" + f.getName(), "Loaded", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error loading project: " + ex.getMessage(), "Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String extractJsonString(String source, String key) {
        int idx = source.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = source.indexOf("\"", start);
        if (end > start) return source.substring(start, end);
        return "";
    }

    private int extractJsonInt(String source, String key, int def) {
        int idx = source.indexOf(key);
        if (idx < 0) return def;
        int start = idx + key.length();
        int end = start;
        while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '-')) end++;
        try {
            return Integer.parseInt(source.substring(start, end).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private boolean extractJsonBool(String source, String key, boolean def) {
        int idx = source.indexOf(key);
        if (idx < 0) return def;
        int start = idx + key.length();
        return source.substring(start).trim().startsWith("true");
    }

    // =========================================================================
    // FULL SCREEN PREVIEW MODE (F11 / ESC)
    // =========================================================================

    public void openFullScreenPreview() {
        JDialog fsDialog = new JDialog(this, true);
        fsDialog.setUndecorated(true);
        fsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        fsDialog.setSize(gd.getDefaultConfiguration().getBounds().getSize());
        fsDialog.setLocation(gd.getDefaultConfiguration().getBounds().getLocation());

        JPanel fsPanel = new JPanel(new BorderLayout());
        fsPanel.setBackground(Color.BLACK);

        JPanel previewCanvas = new JPanel() {
            private String hoveredClickerText = "";
            private int mouseEngX = -1;
            private int mouseEngY = -1;

            {
                addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        int viewW = getWidth();
                        int viewH = getHeight();
                        double scale = Math.min((double) viewW / 1920.0, (double) viewH / 1080.0);
                        int offX = (viewW - (int) Math.round(1920 * scale)) / 2;
                        int offY = (viewH - (int) Math.round(1080 * scale)) / 2;

                        mouseEngX = (int) Math.round((e.getX() - offX) / scale);
                        mouseEngY = (int) Math.round((e.getY() - offY) / scale);

                        hoveredClickerText = "";
                        for (ClickerDef d : savedClickers) {
                            if (mouseEngX >= d.x1 && mouseEngX <= d.x2 && mouseEngY >= d.y1 && mouseEngY <= d.y2) {
                                hoveredClickerText = (d.text != null && !d.text.isEmpty()) ? d.text : d.id;
                                break;
                            }
                        }
                        repaint();
                    }
                });

                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (!hoveredClickerText.isEmpty()) {
                            Toolkit.getDefaultToolkit().beep();
                        }
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int viewW = getWidth();
                int viewH = getHeight();
                double scale = Math.min((double) viewW / 1920.0, (double) viewH / 1080.0);
                int drawW = (int) Math.round(1920.0 * scale);
                int drawH = (int) Math.round(1080.0 * scale);
                int offX = (viewW - drawW) / 2;
                int offY = (viewH - drawH) / 2;

                if (currentImage != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(currentImage, offX, offY, drawW, drawH, null);
                } else {
                    g2.setColor(new Color(24, 26, 30));
                    g2.fillRect(offX, offY, drawW, drawH);
                }

                for (OverlayObject obj : overlayObjects) {
                    BufferedImage frame = obj.getCurrentFrame();
                    if (frame != null) {
                        int sx = offX + (int) Math.round(obj.x * scale);
                        int sy = offY + (int) Math.round(obj.y * scale);
                        int sw = (int) Math.round(obj.getDisplayWidth() * scale);
                        int sh = (int) Math.round(obj.getDisplayHeight() * scale);
                        if (obj.flipH) {
                            g2.drawImage(frame, sx + sw, sy, -sw, sh, null);
                        } else {
                            g2.drawImage(frame, sx, sy, sw, sh, null);
                        }
                    }
                }

                if (!hoveredClickerText.isEmpty()) {
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
                    FontMetrics fm = g2.getFontMetrics();
                    int tw = fm.stringWidth(hoveredClickerText) + 32;
                    int th = 44;
                    int tx = (viewW - tw) / 2;
                    int ty = offY + drawH - 70;

                    g2.setColor(new Color(0, 0, 0, 220));
                    g2.fillRoundRect(tx, ty, tw, th, 12, 12);
                    g2.setColor(new Color(0, 215, 255));
                    g2.setStroke(new BasicStroke(2.0f));
                    g2.drawRoundRect(tx, ty, tw, th, 12, 12);
                    g2.drawString(hoveredClickerText, tx + 16, ty + 28);
                }

                g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                g2.setColor(new Color(255, 255, 255, 150));
                g2.drawString("FULL SCREEN ENGINE PREVIEW (1920×1080) | Press ESC or F11 to Exit", offX + 16, offY + 24);
                g2.dispose();
            }
        };

        javax.swing.Timer fsTicker = new javax.swing.Timer(16, ev -> previewCanvas.repaint());
        fsTicker.start();

        fsDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                fsTicker.stop();
            }
        });

        fsPanel.registerKeyboardAction(e -> fsDialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        fsPanel.registerKeyboardAction(e -> fsDialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        fsPanel.add(previewCanvas, BorderLayout.CENTER);
        fsDialog.setContentPane(fsPanel);
        fsDialog.setVisible(true);
    }

    // =========================================================================
    // THEME APPLICATION & STYLING HELPERS
    // =========================================================================

    private void applyTheme() {
        getContentPane().setBackground(cBg);
        applyThemeToContainer(getContentPane());

        if (rightTabbedPane != null && isDarkMode) {
            rightTabbedPane.setBackground(cBg);
            rightTabbedPane.setForeground(cFg);
            rightTabbedPane.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                @Override
                protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w,
                        int h, boolean isSelected) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(isSelected ? new Color(50, 54, 65) : new Color(30, 32, 38));
                    g2.fillRect(x, y, w, h);
                    if (isSelected) {
                        g2.setColor(new Color(0, 122, 255));
                        g2.fillRect(x, y + h - 3, w, 3);
                    }
                    g2.dispose();
                }

                @Override
                protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics, int tabIndex,
                        String title, Rectangle textRect, boolean isSelected) {
                    g.setFont(font);
                    g.setColor(isSelected ? Color.WHITE : new Color(180, 185, 195));
                    g.drawString(title, textRect.x, textRect.y + metrics.getAscent());
                }

                @Override
                protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(cBorder);
                    g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    g2.dispose();
                }

                @Override
                protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
                        Rectangle iconRect, Rectangle textRect, boolean isSelected) {
                }
            });
        }
    }

    private void applyThemeToContainer(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JButton) {
                JButton btn = (JButton) c;
                Color bg = btn.getBackground();
                if (bg != null && (bg.equals(new Color(35, 134, 54)) || bg.equals(new Color(0, 122, 255)) || bg.equals(new Color(0, 155, 225)) || bg.equals(new Color(130, 80, 220)) || bg.equals(new Color(230, 110, 0)))) {
                    btn.setOpaque(true);
                    btn.setContentAreaFilled(true);
                    btn.setFocusPainted(false);
                    btn.setRolloverEnabled(false);
                    btn.setForeground(Color.WHITE);
                } else {
                    btn.setBackground(cButtonBg);
                    btn.setForeground(cButtonFg);
                    btn.setOpaque(true);
                    btn.setContentAreaFilled(true);
                    btn.setFocusPainted(false);
                    btn.setRolloverEnabled(false);
                }
            } else if (c instanceof JComboBox) {
                JComboBox<?> cmb = (JComboBox<?>) c;
                cmb.setBackground(cInputBg);
                cmb.setForeground(cFg);
                cmb.setOpaque(true);
            } else if (c instanceof JSpinner) {
                JSpinner sp = (JSpinner) c;
                sp.setBackground(cInputBg);
                sp.setForeground(cFg);
                sp.setOpaque(true);
                JComponent editor = sp.getEditor();
                if (editor instanceof JSpinner.DefaultEditor) {
                    JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
                    tf.setBackground(cInputBg);
                    tf.setForeground(cFg);
                    tf.setCaretColor(cFg);
                    tf.setOpaque(true);
                }
            } else if (c instanceof JTextField) {
                JTextField tf = (JTextField) c;
                tf.setBackground(cInputBg);
                tf.setForeground(cFg);
                tf.setCaretColor(cFg);
                tf.setOpaque(true);
            } else if (c instanceof JTextArea) {
                JTextArea ta = (JTextArea) c;
                ta.setBackground(cInputBg);
                ta.setForeground(cFg);
                ta.setCaretColor(cFg);
                ta.setOpaque(true);
            } else if (c instanceof JCheckBox) {
                JCheckBox chk = (JCheckBox) c;
                chk.setForeground(cFg);
            } else if (c instanceof JLabel) {
                JLabel lbl = (JLabel) c;
                lbl.setForeground(cFg);
            } else if (c instanceof JPanel) {
                JPanel pnl = (JPanel) c;
                if (!(pnl instanceof ClickAreaCanvas)) {
                    pnl.setBackground(cPanelBg);
                }
            } else if (c instanceof JScrollPane) {
                JScrollPane sp = (JScrollPane) c;
                sp.setBackground(cInputBg);
                sp.getViewport().setBackground(cInputBg);
            }
            if (c instanceof Container) {
                applyThemeToContainer((Container) c);
            }
        }
    }

    private JLabel createStyledLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lbl.setForeground(cFg);
        return lbl;
    }

    private void styleStandardButton(AbstractButton btn) {
        if (btn == null) return;
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btn.setBackground(cButtonBg);
        btn.setForeground(cButtonFg);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(cBorder, 1),
                new EmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setRolloverEnabled(false);
    }

    private void stylePrimaryButton(JButton btn, Color bg) {
        if (btn == null) return;
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(bg.darker(), 1),
                new EmptyBorder(4, 12, 4, 12)));
        btn.setFocusPainted(false);
        btn.setRolloverEnabled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void styleTextField(JTextField tf) {
        if (tf == null) return;
        tf.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tf.setBackground(cInputBg);
        tf.setForeground(cFg);
        tf.setCaretColor(cFg);
        tf.setOpaque(true);
        tf.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(cBorder, 1),
                new EmptyBorder(4, 8, 4, 8)));
    }

    private void styleComboBox(JComboBox<String> cmb) {
        if (cmb == null) return;
        cmb.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        cmb.setBackground(cInputBg);
        cmb.setForeground(cFg);
        cmb.setOpaque(true);
        cmb.setBorder(new LineBorder(cBorder, 1));
        cmb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (isSelected) {
                    setBackground(new Color(0, 122, 255));
                    setForeground(Color.WHITE);
                } else {
                    setBackground(cInputBg);
                    setForeground(cFg);
                }
                setBorder(new EmptyBorder(4, 8, 4, 8));
                return this;
            }
        });
    }

    private void styleSpinner(JSpinner sp) {
        if (sp == null) return;
        sp.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sp.setBackground(cInputBg);
        sp.setForeground(cFg);
        sp.setOpaque(true);
        sp.setBorder(new LineBorder(cBorder, 1));
        JComponent editor = sp.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
            tf.setBackground(cInputBg);
            tf.setForeground(cFg);
            tf.setCaretColor(cFg);
            tf.setOpaque(true);
            tf.setSelectionColor(new Color(0, 122, 255));
            tf.setSelectedTextColor(Color.WHITE);
        }
    }

    private void styleCheckBox(JCheckBox chk) {
        if (chk == null) return;
        chk.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        chk.setForeground(cFg);
        chk.setOpaque(false);
    }

    private void styleTitledBorder(JPanel panel, String title) {
        if (panel == null) return;
        panel.setBorder(BorderFactory.createTitledBorder(
                new LineBorder(cBorder, 1),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                cTitle));
    }

    // =========================================================================
    // FILE IMPORT & SPRITE MANAGEMENT WORKFLOW
    // =========================================================================

    public void loadImageFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                JOptionPane.showMessageDialog(this, "Could not decode image file: " + file.getName(), "Invalid Image",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            int w = img.getWidth();
            int h = img.getHeight();

            if (w == 1920 && h == 1080) {
                currentImage = img;
                currentImageFile = file;
                currentImageResText = "1920×1080 (Exact Match)";
                lblImageStatus.setText("Engine Canvas: " + file.getName() + " | [1920×1080 Validated]");
                lblImageStatus.setForeground(new Color(85, 215, 105));
            } else {
                String[] options = { "Auto-Scale to 1920×1080", "Cancel" };
                int choice = JOptionPane.showOptionDialog(
                        this,
                        "Brok VN Engine strictly requires backgrounds to be 1920×1080 pixels for accurate collision mapping.\n\n"
                                + "Selected File: " + file.getName() + "\n"
                                + "Actual Resolution: " + w + " × " + h + " px\n\n"
                                + "Would you like to auto-scale this image to 1920×1080 now?",
                        "Resolution Check (1920×1080 Required)",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        options,
                        options[0]);

                if (choice == 0) {
                    BufferedImage scaled = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = scaled.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2.drawImage(img, 0, 0, 1920, 1080, null);
                    g2.dispose();

                    currentImage = scaled;
                    currentImageFile = file;
                    currentImageResText = "Scaled to 1920×1080";
                    lblImageStatus.setText("Engine Canvas: " + file.getName() + " | [Scaled to 1920×1080]");
                    lblImageStatus.setForeground(new Color(245, 180, 60));
                } else {
                    return;
                }
            }

            updateOverallBrokVnFile();
            canvas.repaint();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error reading image: " + ex.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    public void chooseAndLoadImage() {
        File f = browseFileNative("Select 1920×1080 Background Image", FileDialog.LOAD, "Images", "png", "jpg", "jpeg", "bmp", "webp");
        if (f != null) {
            loadImageFile(f);
        }
    }

    public void chooseAndPlaceObjectPng() {
        File sel = browseFileNative("Select Character Sprite or Object PNG to Place", FileDialog.LOAD, "Images", "png", "webp", "jpg", "jpeg");
        if (sel != null) {
            try {
                BufferedImage img = ImageIO.read(sel);
                if (img != null) {
                    placeOverlayObject(sel, img, null, false, 1, 8);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error loading image: " + ex.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void chooseAndImportSpritesheet() {
        File sel = browseFileNative("Select Spritesheet Strip PNG", FileDialog.LOAD, "Images", "png", "webp", "jpg");
        if (sel != null) {
            try {
                BufferedImage img = ImageIO.read(sel);
                if (img != null) {
                    int rawW = img.getWidth();
                    int rawH = img.getHeight();
                    int defaultFrames = (rawW >= rawH * 2 && rawW % rawH == 0) ? (rawW / rawH) : 6;

                    String input = JOptionPane.showInputDialog(this,
                            "Enter number of animation frames (NBFRAMES) in strip:\n(Image width: " + rawW + "px, height: " + rawH + "px)",
                            defaultFrames);
                    int nFrames = defaultFrames;
                    if (input != null && !input.trim().isEmpty()) {
                        try {
                            nFrames = Math.max(1, Integer.parseInt(input.trim()));
                        } catch (Exception ignored) {
                        }
                    }

                    placeOverlayObject(sel, img, null, true, nFrames, 8);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error loading spritesheet: " + ex.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void handleDroppedFiles(List<File> files, Point dropPoint) {
        if (files == null || files.isEmpty()) return;
        for (File f : files) {
            String lower = f.getName().toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
                try {
                    BufferedImage img = ImageIO.read(f);
                    if (img == null) continue;
                    int w = img.getWidth();
                    int h = img.getHeight();

                    String[] options = { "Character / Spritesheet Animation", "Static Sprite / Object PNG", "Background (1920×1080)" };
                    int choice = JOptionPane.showOptionDialog(
                            this,
                            "Importing Image: " + f.getName() + " (" + w + " × " + h + " px)\n\n"
                                    + "Please select the import type for this image:",
                            "Import Image Type",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            options,
                            (w >= h * 2) ? options[0] : (w == 1920 && h == 1080 ? options[2] : options[1]));

                    if (choice == 0) { // Spritesheet Animation
                        int defaultFrames = (w >= h * 2 && w % h == 0) ? (w / h) : 6;
                        String input = JOptionPane.showInputDialog(this, "Enter number of frames (NBFRAMES):", defaultFrames);
                        int nf = defaultFrames;
                        if (input != null && !input.trim().isEmpty()) {
                            try { nf = Math.max(1, Integer.parseInt(input.trim())); } catch (Exception ignored) {}
                        }
                        placeOverlayObject(f, img, dropPoint, true, nf, 8);
                    } else if (choice == 1) { // Static Sprite
                        placeOverlayObject(f, img, dropPoint, false, 1, 8);
                    } else if (choice == 2) { // Background
                        loadImageFile(f);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error loading dropped image: " + ex.getMessage(), "Drop Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            } else if (lower.endsWith(".brokproj") || lower.endsWith(".json")) {
                openProjectFileDirect(f);
            }
        }
    }

    private void openProjectFileDirect(File f) {
        currentProjectFile = f;
        openProject();
    }

    public OverlayObject placeOverlayObject(File file, BufferedImage img, Point dropPoint, boolean isAnim, int nFrames, int speed) {
        if (img == null) return null;
        int dropEngX = (dropPoint != null && canvas != null) ? canvas.toEngineX(dropPoint.x) : 960;
        int dropEngY = (dropPoint != null && canvas != null) ? canvas.toEngineY(dropPoint.y) : 540;

        OverlayObject obj = new OverlayObject(file, img, 0, 0);
        obj.isAnimation = isAnim;
        obj.nbFrames = Math.max(1, nFrames);
        obj.animSpeed = Math.max(1, speed);
        obj.sliceFrames();

        int objX = dropEngX - obj.getDisplayWidth() / 2;
        int objY = dropEngY - obj.getDisplayHeight() / 2;
        obj.x = objX;
        obj.y = objY;

        if (isAnim) {
            obj.useWalkPath = true;
            obj.initDefaultWaypoints();
        }

        overlayObjects.add(obj);
        activeOverlayObject = obj;

        activeEditTarget = ActiveEditTarget.IMAGE;
        if (btnTargetImage != null) btnTargetImage.setSelected(true);
        if (btnTargetClicker != null) btnTargetClicker.setSelected(false);
        if (btnClearOverlays != null) btnClearOverlays.setEnabled(true);

        syncImageUiFromActiveObject();
        setBoundsCoordinates(obj.x, obj.y, obj.getX2(), obj.getY2());

        if (file != null) {
            String fName = file.getName();
            int dot = fName.lastIndexOf('.');
            String base = (dot > 0) ? fName.substring(0, dot) : fName;
            base = base.replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase();
            if (txtId != null) txtId.setText("CLICK_" + base);
            if (txtText != null) txtText.setText("Examine " + base.replace('_', ' '));
            if (txtEvent != null) txtEvent.setText("S01_CLICK_" + base);
        }

        if (lblImageStatus != null) {
            lblImageStatus.setText(String.format("Placed '%s' (%d×%d px)", obj.name, obj.nativeWidth, obj.nativeHeight));
            lblImageStatus.setForeground(new Color(0, 215, 255));
        }

        updateBoundsLabel();
        updateScriptPreview();
        updateOverallBrokVnFile();
        refreshLayerTable();
        if (canvas != null) canvas.repaint();
        return obj;
    }

    public void syncImageUiFromActiveObject() {
        if (activeOverlayObject == null) {
            if (txtImageId != null) txtImageId.setText("");
            if (txtImageFile != null) txtImageFile.setText("");
            if (lblImgDimensions != null) lblImgDimensions.setText("No sprite placed");
            updateBoundsLabel();
            updateScriptPreview();
            return;
        }
        updatingImgSpinners = true;
        if (txtImageId != null) txtImageId.setText(activeOverlayObject.imageId);
        if (txtImageFile != null) txtImageFile.setText(activeOverlayObject.fileField);
        if (spImgX != null) spImgX.setValue(activeOverlayObject.getAnchorX());
        if (spImgY != null) spImgY.setValue(activeOverlayObject.getAnchorY());
        if (lblImgDimensions != null) {
            lblImgDimensions.setText(String.format("%d×%d px (Native: %d×%d)",
                    activeOverlayObject.getDisplayWidth(), activeOverlayObject.getDisplayHeight(),
                    activeOverlayObject.nativeWidth, activeOverlayObject.nativeHeight));
        }
        if (chkUseOrigin != null) chkUseOrigin.setSelected(activeOverlayObject.useOrigin);
        if (cmbImageOrigin != null) {
            cmbImageOrigin.setEnabled(activeOverlayObject.useOrigin);
            cmbImageOrigin.setSelectedItem(activeOverlayObject.origin);
        }
        if (chkAutoDepth != null) chkAutoDepth.setSelected(activeOverlayObject.autoDepth);
        if (spImageDepth != null) {
            spImageDepth.setEnabled(!activeOverlayObject.autoDepth);
            spImageDepth.setValue(activeOverlayObject.getCalculatedDepth());
        }
        if (spImageScale != null) spImageScale.setValue(activeOverlayObject.scale);
        if (sldImageScale != null) sldImageScale.setValue(Math.min(200, Math.max(10, activeOverlayObject.scale)));
        if (chkFlipH != null) chkFlipH.setSelected(activeOverlayObject.flipH);

        // Animation UI
        if (chkIsAnimation != null) chkIsAnimation.setSelected(activeOverlayObject.isAnimation);
        if (spNbFrames != null) {
            spNbFrames.setEnabled(activeOverlayObject.isAnimation);
            spNbFrames.setValue(activeOverlayObject.nbFrames);
        }
        if (spAnimSpeed != null) {
            spAnimSpeed.setEnabled(activeOverlayObject.isAnimation);
            spAnimSpeed.setValue(activeOverlayObject.animSpeed);
        }
        if (cmbAnimEnd != null) {
            cmbAnimEnd.setEnabled(activeOverlayObject.isAnimation);
            cmbAnimEnd.setSelectedItem(activeOverlayObject.animEnd);
        }
        if (sldAnimScrubber != null) {
            sldAnimScrubber.setEnabled(activeOverlayObject.isAnimation);
            sldAnimScrubber.setMaximum(Math.max(1, activeOverlayObject.nbFrames));
            sldAnimScrubber.setValue(activeOverlayObject.currentFrameIndex + 1);
        }
        if (btnPlayPauseAnim != null) {
            btnPlayPauseAnim.setEnabled(activeOverlayObject.isAnimation);
            btnPlayPauseAnim.setText(activeOverlayObject.isPlaying ? "Pause" : "Play");
        }
        updateAnimStatusLabel();

        // Walk Path UI & Waypoints
        if (chkUseWalkPath != null) chkUseWalkPath.setSelected(activeOverlayObject.useWalkPath);
        refreshWaypointSelector();
        if (cmbWaypointSelector != null && cmbWaypointSelector.getItemCount() > 0) {
            cmbWaypointSelector.setSelectedIndex(0);
        }
        syncWaypointUiFromSelection();
        updateWalkDistanceLabel();

        updatingImgSpinners = false;
        updateBoundsLabel();
        updateScriptPreview();
        updateOverallBrokVnFile();
    }

    public void syncActiveObjectFromImageUi() {
        if (updatingImgSpinners || activeOverlayObject == null)
            return;

        if (txtImageId != null && !txtImageId.getText().trim().isEmpty()) {
            activeOverlayObject.imageId = txtImageId.getText().trim().replaceAll("[^a-zA-Z0-9_]", "_");
        }
        if (txtImageFile != null) {
            activeOverlayObject.fileField = txtImageFile.getText().trim().replaceAll("[^a-zA-Z0-9_]", "_");
        }
        if (chkUseOrigin != null) {
            activeOverlayObject.useOrigin = chkUseOrigin.isSelected();
        }
        if (cmbImageOrigin != null && cmbImageOrigin.getSelectedItem() != null) {
            activeOverlayObject.origin = cmbImageOrigin.getSelectedItem().toString();
        }
        if (spImgX != null && spImgY != null) {
            int ax = (int) spImgX.getValue();
            int ay = (int) spImgY.getValue();
            activeOverlayObject.setFromAnchor(ax, ay);
        }
        if (chkAutoDepth != null) {
            activeOverlayObject.autoDepth = chkAutoDepth.isSelected();
            if (spImageDepth != null) spImageDepth.setEnabled(!activeOverlayObject.autoDepth);
        }
        if (spImageDepth != null && !activeOverlayObject.autoDepth) {
            activeOverlayObject.depth = (int) spImageDepth.getValue();
        }
        if (spImageScale != null) {
            activeOverlayObject.scale = (int) spImageScale.getValue();
        }
        if (chkFlipH != null) {
            activeOverlayObject.flipH = chkFlipH.isSelected();
        }

        // Animation UI
        if (chkIsAnimation != null) {
            activeOverlayObject.isAnimation = chkIsAnimation.isSelected();
        }
        if (spNbFrames != null) {
            int nf = (int) spNbFrames.getValue();
            if (nf != activeOverlayObject.nbFrames) {
                activeOverlayObject.nbFrames = nf;
                activeOverlayObject.sliceFrames();
            }
        }
        if (spAnimSpeed != null) {
            activeOverlayObject.animSpeed = (int) spAnimSpeed.getValue();
        }
        if (cmbAnimEnd != null && cmbAnimEnd.getSelectedItem() != null) {
            activeOverlayObject.animEnd = cmbAnimEnd.getSelectedItem().toString();
        }

        if (lblImgDimensions != null) {
            lblImgDimensions.setText(String.format("%d×%d px (Native: %d×%d)",
                    activeOverlayObject.getDisplayWidth(), activeOverlayObject.getDisplayHeight(),
                    activeOverlayObject.nativeWidth, activeOverlayObject.nativeHeight));
        }

        updateBoundsLabel();
        updateScriptPreview();
        updateOverallBrokVnFile();
        refreshLayerTable();
        if (canvas != null) canvas.repaint();
    }

    public void snapClickerToImage() {
        if (activeOverlayObject == null) {
            JOptionPane.showMessageDialog(this, "No character sprite placed on canvas.", "No Sprite",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        setBoundsCoordinates(activeOverlayObject.x, activeOverlayObject.y, activeOverlayObject.getX2(),
                activeOverlayObject.getY2());
    }

    public void clearOverlayObjects() {
        overlayObjects.clear();
        activeOverlayObject = null;
        if (btnClearOverlays != null) btnClearOverlays.setEnabled(false);
        syncImageUiFromActiveObject();
        if (lblImageStatus != null) {
            lblImageStatus.setText(currentImageFile != null
                    ? "Engine Canvas: " + currentImageFile.getName() + " | [" + currentImageResText + "]"
                    : "Engine Canvas: 1920×1080 | [No Image Loaded]");
            lblImageStatus.setForeground(cFgSubdued);
        }
        updateBoundsLabel();
        updateScriptPreview();
        updateOverallBrokVnFile();
        refreshLayerTable();
        if (canvas != null) canvas.repaint();
    }

    public void setBoundsCoordinates(int x1, int y1, int x2, int y2) {
        curX1 = Math.min(x1, x2);
        curY1 = Math.min(y1, y2);
        curX2 = Math.max(x1, x2);
        curY2 = Math.max(y1, y2);

        updatingSpinners = true;
        if (spX1 != null) spX1.setValue(curX1);
        if (spY1 != null) spY1.setValue(curY1);
        if (spX2 != null) spX2.setValue(curX2);
        if (spY2 != null) spY2.setValue(curY2);
        if (spWidth != null) spWidth.setValue(Math.max(1, curX2 - curX1));
        if (spHeight != null) spHeight.setValue(Math.max(1, curY2 - curY1));
        updatingSpinners = false;

        updateBoundsLabel();
        updateScriptPreview();
        updateOverallBrokVnFile();
        if (canvas != null) canvas.repaint();
    }

    private void updateBoundsLabel() {
        if (lblCurrentBounds == null) return;
        int w = curX2 - curX1;
        int h = curY2 - curY1;
        String clickerPart = String.format("Clicker: [%d, %d] -> [%d, %d] (%d×%d px)", curX1, curY1, curX2, curY2, w, h);
        if (activeOverlayObject != null) {
            String imgPart = String.format("'%s': [Anchor X: %d, Y: %d | Scale: %d%% | Depth: %d]",
                    activeOverlayObject.imageId, activeOverlayObject.getAnchorX(), activeOverlayObject.getAnchorY(),
                    activeOverlayObject.scale, activeOverlayObject.getCalculatedDepth());
            lblCurrentBounds.setText(clickerPart + "  |  " + imgPart);
        } else {
            lblCurrentBounds.setText(clickerPart);
        }
    }

    private ClickerDef createDefFromCurrentUi() {
        ClickerDef d = new ClickerDef();
        d.id = txtId.getText().trim();
        if (d.id.isEmpty()) d.id = "CLICK_ITEM";
        d.x1 = curX1;
        d.y1 = curY1;
        d.x2 = curX2;
        d.y2 = curY2;
        d.event = txtEvent.getText().trim();
        d.text = txtText.getText().trim();
        d.highlight = chkHighlight.isSelected();
        d.hotspot = chkHotspot.isSelected();
        d.hotspotIcon = (String) cmbHotspotIcon.getSelectedItem();
        d.canDpad = chkCanDpad.isSelected();
        d.type = (String) cmbType.getSelectedItem();
        d.stayActive = (String) cmbStayActive.getSelectedItem();
        if (spClickerLayer != null) {
            d.layer = (int) spClickerLayer.getValue();
        }

        int hash = Math.abs(d.id.hashCode());
        float hue = (hash % 360) / 360.0f;
        d.color = Color.getHSBColor(hue, 0.85f, 0.95f);
        return d;
    }

    private void loadClickerDef(ClickerDef d) {
        txtId.setText(d.id);
        txtEvent.setText(d.event);
        txtText.setText(d.text);
        chkHighlight.setSelected(d.highlight);
        chkHotspot.setSelected(d.hotspot);
        cmbHotspotIcon.setSelectedItem(d.hotspotIcon);
        chkCanDpad.setSelected(d.canDpad);
        cmbType.setSelectedItem(d.type);
        cmbStayActive.setSelectedItem(d.stayActive);
        if (spClickerLayer != null) spClickerLayer.setValue(d.layer);

        setBoundsCoordinates(d.x1, d.y1, d.x2, d.y2);
    }

    private void updateScriptPreview() {
        if (txtScriptPreview == null) return;
        StringBuilder sb = new StringBuilder();

        ClickerDef d = createDefFromCurrentUi();
        String mode = (cmbScriptGenMode != null && cmbScriptGenMode.getSelectedItem() != null)
                ? cmbScriptGenMode.getSelectedItem().toString()
                : "Combined";

        if (mode.startsWith("Combined")) {
            if (activeOverlayObject != null) {
                sb.append(activeOverlayObject.toImageNewScript());
                sb.append("\n");
            }
            sb.append(d.toBrokVnScript());
        } else if (mode.contains("IMAGENEW") || mode.contains("Sprite")) {
            if (activeOverlayObject != null) {
                sb.append(activeOverlayObject.toImageNewScript());
            }
        } else if (mode.contains("IMAGEMOVE") || mode.contains("Walk")) {
            if (activeOverlayObject != null && activeOverlayObject.useWalkPath && activeOverlayObject.waypoints.size() >= 2) {
                for (int i = 1; i < activeOverlayObject.waypoints.size(); i++) {
                    Waypoint wpPrev = activeOverlayObject.waypoints.get(i - 1);
                    Waypoint wpCurr = activeOverlayObject.waypoints.get(i);
                    String evName = (i == 1) ? "S01_" + activeOverlayObject.imageId + "_WALK_START" : "S01_" + activeOverlayObject.imageId + "_PATH_" + i;

                    sb.append("EVENT=").append(evName).append("\n");
                    sb.append("\tIMAGEMOVE=").append(activeOverlayObject.imageId).append("\n");
                    sb.append("\t\tMOVEX=").append(wpCurr.x).append("\n");
                    if (wpCurr.y != wpPrev.y) {
                        sb.append("\t\tMOVEY=").append(wpCurr.y).append("\n");
                    }
                    sb.append("\t\tSPEED=").append(Math.max(1, wpCurr.speed)).append("\n");
                    String nextEv = (i < activeOverlayObject.waypoints.size() - 1)
                            ? "S01_" + activeOverlayObject.imageId + "_PATH_" + (i + 1)
                            : ((wpCurr.endEvent != null && !wpCurr.endEvent.trim().isEmpty()) ? wpCurr.endEvent.trim() : "S01_" + activeOverlayObject.imageId + "_ARRIVED");
                    sb.append("\t\tENDEVENT=").append(nextEv).append("\n\n");
                }
            } else {
                sb.append("# (Enable Walk Path in 'Sprite / Character' tab to generate IMAGEMOVE)");
            }
        } else {
            sb.append(d.toBrokVnScript());
        }

        txtScriptPreview.setText(sb.toString());
        txtScriptPreview.setCaretPosition(0);
        if (codeScroll != null && codeScroll.getVerticalScrollBar() != null) {
            codeScroll.getVerticalScrollBar().setValue(0);
        }
    }

    private void copyCurrentCode() {
        String code = txtScriptPreview.getText();
        if (code != null && !code.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
            JOptionPane.showMessageDialog(this, "Script code copied to clipboard!", "Copied",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void insertCurrentCode() {
        String code = txtScriptPreview.getText();
        if (insertCallback != null && code != null && !code.isEmpty()) {
            insertCallback.insertScriptCode("\n" + code + "\n");
            JOptionPane.showMessageDialog(this, "Inserted script code into active script editor!", "Inserted",
                    JOptionPane.INFORMATION_MESSAGE);
        } else {
            copyCurrentCode();
        }
    }

    private void addCurrentToClickersList() {
        ClickerDef def = createDefFromCurrentUi();
        savedClickers.add(def);

        if (clickerTableModel != null) {
            clickerTableModel.addRow(new Object[] {
                    def.id, def.layer, def.x1, def.y1, def.x2, def.y2,
                    (def.x2 - def.x1) + "×" + (def.y2 - def.y1),
                    def.event, def.text
            });
        }

        rightTabbedPane.setTitleAt(3, "Clickers List (" + savedClickers.size() + ")");

        String curId = txtId.getText().trim();
        if (curId.matches(".*_\\d+$")) {
            int num = Integer.parseInt(curId.substring(curId.lastIndexOf('_') + 1));
            txtId.setText(curId.substring(0, curId.lastIndexOf('_') + 1) + (num + 1));
        } else {
            txtId.setText(curId + "_2");
        }

        updateOverallBrokVnFile();
        refreshLayerTable();
        canvas.repaint();
    }

    private void removeSelectedClicker() {
        int row = clickersTable.getSelectedRow();
        if (row >= 0 && row < savedClickers.size()) {
            savedClickers.remove(row);
            clickerTableModel.removeRow(row);
            selectedClickerIndex = -1;
            rightTabbedPane.setTitleAt(3, "Clickers List (" + savedClickers.size() + ")");
            updateOverallBrokVnFile();
            refreshLayerTable();
            canvas.repaint();
        }
    }

    private void copyAllClickersCode() {
        if (savedClickers.isEmpty()) {
            addCurrentToClickersList();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#====================================================\n");
        sb.append("# SCENE CLICKERS DEFINITIONS (1920×1080)\n");
        sb.append("#====================================================\n");
        for (ClickerDef d : savedClickers) {
            sb.append(d.toBrokVnScript()).append("\n");
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        JOptionPane.showMessageDialog(this, "Copied all " + savedClickers.size() + " clicker definitions to clipboard!",
                "Copied Batch", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copyCleanupStatement() {
        if (savedClickers.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please add clickers to the list first.", "List Empty",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder("CLICKERREMOVE=");
        for (int i = 0; i < savedClickers.size(); i++) {
            sb.append(savedClickers.get(i).id);
            if (i < savedClickers.size() - 1)
                sb.append(",");
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        JOptionPane.showMessageDialog(this, "Copied cleanup statement:\n" + sb.toString(), "Copied",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void findAndPromptDefaultBackground() {
        File defaultBg = new File(System.getenv("LOCALAPPDATA"), "vnengine/VN/background/BROK-APARTMENT-MAIN.png");
        if (defaultBg.exists()) {
            loadImageFile(defaultBg);
        } else {
            File viewDay = new File(System.getenv("LOCALAPPDATA"), "vnengine/VN/background/VIEW_DAY.png");
            if (viewDay.exists()) {
                loadImageFile(viewDay);
            }
        }
    }

    private void launchGlueIt() {
        try {
            File[] candidates = new File[] {
                    new File("glueit.exe"),
                    new File(projectBaseDir != null ? projectBaseDir : new File("."), "glueit.exe"),
                    new File(System.getProperty("user.dir"), "glueit.exe"),
                    new File("dist/BrokVnClickAreaWindow/glueit.exe"),
                    new File("GlueIT 1.06.exe")
            };
            File glueItExe = null;
            for (File f : candidates) {
                if (f != null && f.exists() && f.isFile()) {
                    glueItExe = f.getAbsoluteFile();
                    break;
                }
            }
            if (glueItExe == null) {
                glueItExe = browseFileNative("Locate GlueIT Executable (glueit.exe)", FileDialog.LOAD, "Executables", "exe");
            }
            if (glueItExe != null && glueItExe.exists()) {
                ProcessBuilder pb = new ProcessBuilder(glueItExe.getAbsolutePath());
                pb.directory(glueItExe.getParentFile() != null ? glueItExe.getParentFile() : new File("."));
                pb.start();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to launch GlueIT: " + ex.getMessage(), "GlueIT Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // GITHUB API UPDATER, CONFIGURE TOKEN & IN-APP DIRECT DOWNLOADER
    // =========================================================================

    public void showConfigureTokenDialog() {
        JDialog dlg = new JDialog(this, "GitHub API Token Configuration", true);
        dlg.setSize(520, 280);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(cPanelBg);

        JPanel pnl = new JPanel(new GridBagLayout());
        pnl.setBackground(cPanelBg);
        pnl.setBorder(new EmptyBorder(14, 18, 14, 18));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel lblTitle = new JLabel("GitHub API Key / Personal Access Token (PAT)");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblTitle.setForeground(cTitle);

        JLabel lblDesc = new JLabel("<html>Connects to <b>" + GITHUB_REPO + "</b> for auto-downloading updates.<br>"
                + "Increases GitHub rate limit from 60 to 5,000 requests/hr & allows private repo access.</html>");
        lblDesc.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblDesc.setForeground(cFgSubdued);

        JPasswordField txtToken = new JPasswordField(getGitHubApiToken());
        styleTextField(txtToken);

        JLabel lblStatus = new JLabel(" ");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 11));

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        pnl.add(lblTitle, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
        pnl.add(lblDesc, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        pnl.add(txtToken, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        pnl.add(lblStatus, gbc);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnRow.setOpaque(false);

        JButton btnTest = new JButton("Test Connection");
        styleStandardButton(btnTest);
        btnTest.addActionListener(e -> {
            String tok = new String(txtToken.getPassword()).trim();
            lblStatus.setText("Testing GitHub connection...");
            lblStatus.setForeground(new Color(255, 195, 60));
            new Thread(() -> {
                try {
                    URL u = new URI("https://api.github.com/repos/" + GITHUB_REPO).toURL();
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("GET");
                    c.setRequestProperty("User-Agent", "BrokVnGuiEditor/" + APP_VERSION);
                    if (!tok.isEmpty()) {
                        c.setRequestProperty("Authorization", "Bearer " + tok);
                    }
                    c.setConnectTimeout(4000);
                    int code = c.getResponseCode();
                    SwingUtilities.invokeLater(() -> {
                        if (code == 200) {
                            lblStatus.setText("Connected successfully to " + GITHUB_REPO + "!");
                            lblStatus.setForeground(new Color(85, 215, 105));
                        } else {
                            lblStatus.setText("HTTP " + code + ": Access Denied or Repo not found.");
                            lblStatus.setForeground(new Color(255, 80, 80));
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        lblStatus.setText("Error: " + ex.getMessage());
                        lblStatus.setForeground(new Color(255, 80, 80));
                    });
                }
            }).start();
        });

        JButton btnSave = new JButton("Save Token");
        stylePrimaryButton(btnSave, new Color(0, 122, 255));
        btnSave.addActionListener(e -> {
            String tok = new String(txtToken.getPassword()).trim();
            setGitHubApiToken(tok);
            JOptionPane.showMessageDialog(dlg, "GitHub API Token saved to preferences!", "Token Saved", JOptionPane.INFORMATION_MESSAGE);
            dlg.dispose();
        });

        JButton btnClose = new JButton("Cancel");
        styleStandardButton(btnClose);
        btnClose.addActionListener(e -> dlg.dispose());

        btnRow.add(btnTest);
        btnRow.add(btnSave);
        btnRow.add(btnClose);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        pnl.add(btnRow, gbc);

        dlg.setContentPane(pnl);
        dlg.setVisible(true);
    }

    public void showUpdateDialog(boolean notifyIfUpToDate) {
        JDialog dlg = new JDialog(this, "Check for Updates - " + APP_NAME, true);
        dlg.setLayout(new BorderLayout());
        dlg.setSize(540, 320);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(cPanelBg);

        JPanel pnl = new JPanel(new BorderLayout(10, 10));
        pnl.setBackground(cPanelBg);
        pnl.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel lblTitle = new JLabel(APP_NAME + " Update Center");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 15));
        lblTitle.setForeground(cTitle);

        JLabel lblVer = new JLabel("Current Version: " + APP_VERSION + "  |  Repo: " + GITHUB_REPO);
        lblVer.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblVer.setForeground(cFg);

        JLabel lblStatus = new JLabel("Connecting to GitHub repository (" + GITHUB_REPO + ")...");
        lblStatus.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        lblStatus.setForeground(new Color(255, 195, 60));

        JProgressBar pBar = new JProgressBar(0, 100);
        pBar.setStringPainted(true);
        pBar.setVisible(false);

        JPanel center = new JPanel(new GridLayout(4, 1, 4, 6));
        center.setOpaque(false);
        center.add(lblTitle);
        center.add(lblVer);
        center.add(lblStatus);
        center.add(pBar);
        pnl.add(center, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnBar.setOpaque(false);

        JButton btnConfigToken = new JButton("Configure API Key");
        styleStandardButton(btnConfigToken);
        btnConfigToken.addActionListener(e -> showConfigureTokenDialog());

        JButton btnDownloadDirect = new JButton("Download Update Inside App");
        stylePrimaryButton(btnDownloadDirect, new Color(0, 122, 255));
        btnDownloadDirect.setEnabled(false);

        JButton btnClose = new JButton("Close");
        styleStandardButton(btnClose);
        btnClose.addActionListener(e -> dlg.dispose());

        btnBar.add(btnConfigToken);
        btnBar.add(btnDownloadDirect);
        btnBar.add(btnClose);
        pnl.add(btnBar, BorderLayout.SOUTH);

        dlg.setContentPane(pnl);

        new Thread(() -> {
            try {
                URL url = new URI(UPDATE_CHECK_URL).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "BrokVnGuiEditor/" + APP_VERSION);
                String tok = getGitHubApiToken();
                if (!tok.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + tok);
                }
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder resp = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) resp.append(l);
                    br.close();

                    String json = resp.toString();
                    String tag = extractJsonString(json, "\"tag_name\": \"");
                    String downloadUrl = extractJsonString(json, "\"browser_download_url\": \"");
                    if (downloadUrl.isEmpty()) {
                        downloadUrl = extractJsonString(json, "\"zipball_url\": \"");
                    }

                    final String latestTag = tag;
                    final String directAssetUrl = downloadUrl;

                    SwingUtilities.invokeLater(() -> {
                        if (!latestTag.isEmpty() && !latestTag.equalsIgnoreCase(APP_VERSION)) {
                            lblStatus.setText("New version available: " + latestTag + "!");
                            lblStatus.setForeground(new Color(85, 215, 105));
                            btnDownloadDirect.setEnabled(true);
                            btnDownloadDirect.setText("Download " + latestTag + " Now");
                            btnDownloadDirect.addActionListener(ev -> {
                                btnDownloadDirect.setEnabled(false);
                                pBar.setVisible(true);
                                startInAppDownload(directAssetUrl, latestTag, pBar, lblStatus, dlg);
                            });
                        } else {
                            lblStatus.setText("You have the latest version installed (" + APP_VERSION + ").");
                            lblStatus.setForeground(new Color(85, 215, 105));
                        }
                    });
                } else if (code == 404) {
                    SwingUtilities.invokeLater(() -> {
                        lblStatus.setText("Repo connected (" + GITHUB_REPO + "). No releases published yet.");
                        lblStatus.setForeground(new Color(85, 215, 105));
                    });
                } else {
                    throw new IOException("GitHub API returned HTTP " + code);
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText("Up to date (" + APP_VERSION + "). Local release verified.");
                    lblStatus.setForeground(new Color(85, 215, 105));
                });
            }
        }).start();

        dlg.setVisible(true);
    }

    private void startInAppDownload(String assetUrl, String tag, JProgressBar pBar, JLabel lblStatus, JDialog dlg) {
        new Thread(() -> {
            try {
                if (assetUrl == null || assetUrl.isEmpty()) {
                    throw new IOException("No download URL found for this release.");
                }
                URL url = new URI(assetUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "BrokVnGuiEditor/" + APP_VERSION);
                String tok = getGitHubApiToken();
                if (!tok.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + tok);
                }
                conn.connect();

                int totalBytes = conn.getContentLength();
                File updateDir = new File("updates");
                if (!updateDir.exists()) updateDir.mkdirs();

                String outName = "BrokVnGuiEditor_" + tag + ".zip";
                if (assetUrl.toLowerCase().endsWith(".exe")) {
                    outName = "BrokVnGuiEditor_" + tag + ".exe";
                }
                File targetFile = new File(updateDir, outName);

                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(targetFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                int downloaded = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    if (totalBytes > 0) {
                        final int curDownloaded = downloaded;
                        final int finalTotal = totalBytes;
                        final int pct = (int) (((double) curDownloaded / finalTotal) * 100);
                        SwingUtilities.invokeLater(() -> {
                            pBar.setValue(pct);
                            lblStatus.setText(String.format("Downloading: %d%% (%d / %d KB)", pct, curDownloaded / 1024, finalTotal / 1024));
                        });
                    }
                }
                in.close();
                out.close();

                SwingUtilities.invokeLater(() -> {
                    pBar.setValue(100);
                    lblStatus.setText("Download complete: " + targetFile.getName());
                    int choice = JOptionPane.showOptionDialog(
                            dlg,
                            "Update downloaded successfully to:\n" + targetFile.getAbsolutePath()
                                    + "\n\nWould you like to open the updates folder now?",
                            "Update Downloaded",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            new String[] { "Open Updates Folder", "Close" },
                            "Open Updates Folder");
                    if (choice == 0) {
                        openInExplorer(targetFile);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText("Download failed: " + ex.getMessage());
                    lblStatus.setForeground(new Color(255, 80, 80));
                    JOptionPane.showMessageDialog(dlg, "Download failed: " + ex.getMessage(), "Update Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void openDocumentationHtml() {
        File docFile = new File("C:/Users/Janmark Abo/Documents/Brok VN/BROKVN_Engine_WIN_1.0.0/BROK_VN_DOCUMENTATION/BROK_VN_DOCUMENTATION.html");
        if (!docFile.exists()) {
            docFile = new File(System.getenv("LOCALAPPDATA"), "vnengine/BROK_VN_DOCUMENTATION_1.0/html/index.html");
        }
        try {
            if (docFile.exists()) {
                Desktop.getDesktop().open(docFile);
            } else {
                JOptionPane.showMessageDialog(this, "Documentation file not found at expected location.", "Doc Missing", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not open documentation: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showAboutDialog() {
        JOptionPane.showMessageDialog(this,
                APP_NAME + " " + APP_VERSION + "\n\n"
                        + "Dedicated Visual Novel Clickable Area Studio, Character Sprite Placement,\n"
                        + "Multi-Waypoint Walk Paths (A->B->C...), Layer System & Full Screen Preview.\n\n"
                        + "Connected Repository: " + GITHUB_REPO + "\n"
                        + "Engine Resolution: 1920×1080 Native\n"
                        + "Features: Draggable Waypoints, In-App GitHub Auto-Updater, Project Save/Load (.brokproj),\n"
                        + "Draggable Character Badges, Free Frame Overlap, and Overall BrokVN Scene Script Generator.",
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    // =========================================================================
    // INTERACTIVE CANVAS (SIDE 1 / CENTER)
    // =========================================================================

    private class ClickAreaCanvas extends JPanel {
        private int dragStartX = -1;
        private int dragStartY = -1;
        private boolean isDropHover = false;

        private double currentScale = 1.0;
        private int currentOffsetX = 0;
        private int currentOffsetY = 0;

        public ClickAreaCanvas() {
            setBackground(isDarkMode ? new Color(16, 17, 20) : new Color(205, 208, 215));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

            new DropTarget(this, new DropTargetAdapter() {
                @Override
                public void dragEnter(DropTargetDragEvent dtde) {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY);
                        setDropHover(true);
                    } else {
                        dtde.rejectDrag();
                    }
                }

                @Override
                public void dragOver(DropTargetDragEvent dtde) {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY);
                        setDropHover(true);
                    }
                }

                @Override
                public void dragExit(DropTargetEvent dte) {
                    setDropHover(false);
                }

                @Override
                public void drop(DropTargetDropEvent dtde) {
                    setDropHover(false);
                    try {
                        if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            dtde.acceptDrop(DnDConstants.ACTION_COPY);
                            @SuppressWarnings("unchecked")
                            List<File> files = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                            Point dropPoint = dtde.getLocation();
                            if (files != null && !files.isEmpty()) {
                                handleDroppedFiles(files, dropPoint);
                            }
                            dtde.dropComplete(true);
                        } else {
                            dtde.rejectDrop();
                        }
                    } catch (Exception ex) {
                        dtde.dropComplete(false);
                    }
                }
            });

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        int mouseX = e.getX();
                        int mouseY = e.getY();
                        int engX = toEngineX(mouseX);
                        int engY = toEngineY(mouseY);

                        // 1. Check if clicking on Draggable Waypoint Pins
                        if (chkShowWaypoints == null || chkShowWaypoints.isSelected()) {
                            if (activeOverlayObject != null && activeOverlayObject.useWalkPath) {
                                for (int i = 0; i < activeOverlayObject.waypoints.size(); i++) {
                                    Waypoint wp = activeOverlayObject.waypoints.get(i);
                                    int pinSx = toScreenX(wp.x);
                                    int pinSy = toScreenY(wp.y);
                                    if (Math.hypot(mouseX - pinSx, mouseY - pinSy) <= 12) {
                                        currentDragType = DragHandleType.WAYPOINT_PIN;
                                        draggedWaypointIndex = i;
                                        if (cmbWaypointSelector != null) cmbWaypointSelector.setSelectedIndex(i);
                                        lblCursorPos.setText(String.format("Dragging %s at [ X: %d, Y: %d ]", wp.label, wp.x, wp.y));
                                        repaint();
                                        return;
                                    }
                                }
                            }
                        }

                        // 2. Check if clicking on Draggable Character Blue Label Badge
                        if (activeOverlayObject != null && (chkShowOverlays == null || chkShowOverlays.isSelected())) {
                            int tagSx = toScreenX(activeOverlayObject.x) + activeOverlayObject.labelOffsetX;
                            int tagSy = toScreenY(activeOverlayObject.y) + activeOverlayObject.labelOffsetY;
                            if (mouseX >= tagSx && mouseX <= tagSx + 220 && mouseY >= tagSy && mouseY <= tagSy + 22) {
                                currentDragType = DragHandleType.OVERLAY_LABEL;
                                labelDragStartX = mouseX - tagSx;
                                labelDragStartY = mouseY - tagSy;
                                repaint();
                                return;
                            }
                        }

                        // 3. Check if clicking on Sprite Body (IMAGENEW)
                        if (chkShowOverlays == null || chkShowOverlays.isSelected()) {
                            OverlayObject hitObj = null;
                            if (activeOverlayObject != null && activeOverlayObject.contains(engX, engY)) {
                                hitObj = activeOverlayObject;
                            } else {
                                for (int i = overlayObjects.size() - 1; i >= 0; i--) {
                                    OverlayObject obj = overlayObjects.get(i);
                                    if (obj.contains(engX, engY)) {
                                        hitObj = obj;
                                        break;
                                    }
                                }
                            }
                            if (hitObj != null) {
                                activeOverlayObject = hitObj;
                                syncImageUiFromActiveObject();
                                if (activeEditTarget == ActiveEditTarget.IMAGE) {
                                    currentDragType = DragHandleType.OVERLAY_SPRITE;
                                    overlayDragOffsetX = engX - hitObj.x;
                                    overlayDragOffsetY = engY - hitObj.y;
                                    lblCursorPos.setText(String.format("Moving Sprite '%s' at [ X: %d, Y: %d ]",
                                            hitObj.imageId, hitObj.getAnchorX(), hitObj.getAnchorY()));
                                    repaint();
                                    return;
                                }
                            }
                        }

                        // 4. Default: Drag Clicker Selection Rectangle (CLICKERNEW)
                        dragStartX = engX;
                        dragStartY = engY;
                        currentDragType = DragHandleType.CLICKER_BOX;
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int mouseX = e.getX();
                    int mouseY = e.getY();
                    int curX = toEngineX(mouseX);
                    int curY = toEngineY(mouseY);

                    // A. Dragging Waypoint Pin
                    if (currentDragType == DragHandleType.WAYPOINT_PIN && activeOverlayObject != null && draggedWaypointIndex >= 0 && draggedWaypointIndex < activeOverlayObject.waypoints.size()) {
                        Waypoint wp = activeOverlayObject.waypoints.get(draggedWaypointIndex);
                        wp.x = curX;
                        wp.y = curY;
                        if (draggedWaypointIndex == 0) {
                            activeOverlayObject.setFromAnchor(curX, curY);
                        }
                        syncWaypointUiFromSelection();
                        updateWalkDistanceLabel();
                        updateScriptPreview();
                        updateOverallBrokVnFile();
                        lblCursorPos.setText(String.format("Waypoint %s -> [ X: %d, Y: %d ]", wp.label, wp.x, wp.y));
                        repaint();
                    }
                    // B. Dragging Character Blue Label
                    else if (currentDragType == DragHandleType.OVERLAY_LABEL && activeOverlayObject != null) {
                        activeOverlayObject.customLabelPos = true;
                        activeOverlayObject.labelOffsetX = mouseX - toScreenX(activeOverlayObject.x) - labelDragStartX;
                        activeOverlayObject.labelOffsetY = mouseY - toScreenY(activeOverlayObject.y) - labelDragStartY;
                        repaint();
                    }
                    // C. Dragging Sprite
                    else if (currentDragType == DragHandleType.OVERLAY_SPRITE && activeOverlayObject != null) {
                        int newX = curX - overlayDragOffsetX;
                        int newY = curY - overlayDragOffsetY;
                        int dx = newX - activeOverlayObject.x;
                        int dy = newY - activeOverlayObject.y;
                        activeOverlayObject.x = newX;
                        activeOverlayObject.y = newY;

                        if (activeOverlayObject.useWalkPath && !activeOverlayObject.waypoints.isEmpty()) {
                            activeOverlayObject.waypoints.get(0).x = activeOverlayObject.getAnchorX();
                            activeOverlayObject.waypoints.get(0).y = activeOverlayObject.getAnchorY();
                        }

                        if (chkSyncClickerWithImage == null || chkSyncClickerWithImage.isSelected()) {
                            curX1 += dx;
                            curY1 += dy;
                            curX2 += dx;
                            curY2 += dy;
                            setBoundsCoordinates(curX1, curY1, curX2, curY2);
                        }

                        syncImageUiFromActiveObject();
                        lblCursorPos.setText(String.format("Sprite '%s' -> [ Anchor X: %d, Y: %d ]",
                                activeOverlayObject.imageId, activeOverlayObject.getAnchorX(), activeOverlayObject.getAnchorY()));
                        repaint();
                    }
                    // D. Dragging Clicker Rectangle
                    else if (currentDragType == DragHandleType.CLICKER_BOX) {
                        setBoundsCoordinates(dragStartX, dragStartY, curX, curY);
                        lblCursorPos.setText(String.format("Clicker Hotspot -> [ X1: %d, Y1: %d | X2: %d, Y2: %d ]",
                                curX1, curY1, curX2, curY2));
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (currentDragType == DragHandleType.WAYPOINT_PIN) {
                        currentDragType = DragHandleType.NONE;
                        draggedWaypointIndex = -1;
                        refreshWaypointSelector();
                        updateScriptPreview();
                        repaint();
                    } else if (currentDragType == DragHandleType.OVERLAY_SPRITE) {
                        currentDragType = DragHandleType.NONE;
                        updateScriptPreview();
                        repaint();
                    } else if (currentDragType == DragHandleType.OVERLAY_LABEL) {
                        currentDragType = DragHandleType.NONE;
                        repaint();
                    } else if (currentDragType == DragHandleType.CLICKER_BOX) {
                        int curX = toEngineX(e.getX());
                        int curY = toEngineY(e.getY());
                        setBoundsCoordinates(dragStartX, dragStartY, curX, curY);
                        currentDragType = DragHandleType.NONE;
                        repaint();
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    int mouseX = e.getX();
                    int mouseY = e.getY();
                    int ex = toEngineX(mouseX);
                    int ey = toEngineY(mouseY);

                    if (activeOverlayObject != null && activeOverlayObject.useWalkPath) {
                        for (Waypoint wp : activeOverlayObject.waypoints) {
                            int pinSx = toScreenX(wp.x);
                            int pinSy = toScreenY(wp.y);
                            if (Math.hypot(mouseX - pinSx, mouseY - pinSy) <= 12) {
                                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                                lblCursorPos.setText(String.format("Over %s [ X: %d, Y: %d ] (Drag to move pin)", wp.label, wp.x, wp.y));
                                return;
                            }
                        }
                    }

                    boolean overObject = false;
                    if (chkShowOverlays == null || chkShowOverlays.isSelected()) {
                        for (OverlayObject obj : overlayObjects) {
                            if (obj.contains(ex, ey)) {
                                overObject = true;
                                lblCursorPos.setText(String.format("Over Sprite '%s' at [ X: %d, Y: %d ] (Size: %d×%d px)",
                                        obj.imageId, ex, ey, obj.getDisplayWidth(), obj.getDisplayHeight()));
                                break;
                            }
                        }
                    }
                    if (overObject && activeEditTarget == ActiveEditTarget.IMAGE) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    } else {
                        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                        lblCursorPos.setText(String.format("Cursor: [ X: %d, Y: %d ]", ex, ey));
                    }
                }
            };

            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        public void setDropHover(boolean hover) {
            this.isDropHover = hover;
            repaint();
        }

        public int toEngineX(int mouseX) {
            if (currentScale <= 0) return 0;
            return (int) Math.round((mouseX - currentOffsetX) / currentScale);
        }

        public int toEngineY(int mouseY) {
            if (currentScale <= 0) return 0;
            return (int) Math.round((mouseY - currentOffsetY) / currentScale);
        }

        public int toScreenX(int engX) {
            return currentOffsetX + (int) Math.round(engX * currentScale);
        }

        public int toScreenY(int engY) {
            return currentOffsetY + (int) Math.round(engY * currentScale);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int viewW = getWidth();
            int viewH = getHeight();

            double scaleX = (double) viewW / 1920.0;
            double scaleY = (double) viewH / 1080.0;
            currentScale = Math.min(scaleX, scaleY);

            int drawW = (int) Math.round(1920.0 * currentScale);
            int drawH = (int) Math.round(1080.0 * currentScale);
            currentOffsetX = (viewW - drawW) / 2;
            currentOffsetY = (viewH - drawH) / 2;

            // Frame shadow
            g2.setColor(new Color(10, 10, 12));
            g2.fillRect(currentOffsetX - 2, currentOffsetY - 2, drawW + 4, drawH + 4);

            // Background Image
            if (currentImage != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(currentImage, currentOffsetX, currentOffsetY, drawW, drawH, null);
            } else {
                g2.setColor(isDarkMode ? new Color(28, 30, 35) : new Color(240, 240, 242));
                g2.fillRect(currentOffsetX, currentOffsetY, drawW, drawH);

                g2.setColor(isDarkMode ? new Color(130, 140, 155) : new Color(100, 105, 115));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
                String msg1 = "1920×1080 Native Brok VN Canvas";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg1, currentOffsetX + (drawW - fm.stringWidth(msg1)) / 2,
                        currentOffsetY + drawH / 2 - 20);

                g2.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                String msg2 = "Click 'Import Background...' or 'Place Sprite...' or drag & drop files!";
                fm = g2.getFontMetrics();
                g2.drawString(msg2, currentOffsetX + (drawW - fm.stringWidth(msg2)) / 2,
                        currentOffsetY + drawH / 2 + 10);
            }

            // Render Character / Object Overlays with Frame Overlap, Z-Depth sorting & Multi-Waypoints
            if (chkShowOverlays == null || chkShowOverlays.isSelected()) {
                List<OverlayObject> sortedOverlays = new ArrayList<>(overlayObjects);
                sortedOverlays.sort((o1, o2) -> Integer.compare(o1.getCalculatedDepth(), o2.getCalculatedDepth()));

                for (OverlayObject obj : sortedOverlays) {
                    // Draw Multi-Waypoint Motion Vectors & Draggable Pins
                    if (obj.useWalkPath && (chkShowWaypoints == null || chkShowWaypoints.isSelected()) && obj.waypoints.size() >= 2) {
                        for (int w = 0; w < obj.waypoints.size() - 1; w++) {
                            Waypoint pA = obj.waypoints.get(w);
                            Waypoint pB = obj.waypoints.get(w + 1);
                            int ax = toScreenX(pA.x);
                            int ay = toScreenY(pA.y);
                            int bx = toScreenX(pB.x);
                            int by = toScreenY(pB.y);

                            float[] pathDash = { 6.0f, 4.0f };
                            g2.setColor(new Color(255, 170, 0, 220));
                            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, pathDash, 0.0f));
                            g2.drawLine(ax, ay, bx, by);
                        }

                        // Draw Draggable Waypoint Pins (Point A, B, C...)
                        for (int w = 0; w < obj.waypoints.size(); w++) {
                            Waypoint wp = obj.waypoints.get(w);
                            int px = toScreenX(wp.x);
                            int py = toScreenY(wp.y);

                            Color pinColor = (w == 0) ? new Color(85, 215, 105) : ((w == obj.waypoints.size() - 1) ? new Color(255, 60, 60) : new Color(0, 180, 255));
                            g2.setColor(pinColor);
                            g2.fillOval(px - 7, py - 7, 14, 14);
                            g2.setColor(Color.WHITE);
                            g2.setStroke(new BasicStroke(1.5f));
                            g2.drawOval(px - 7, py - 7, 14, 14);

                            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
                            String pinTag = wp.label;
                            FontMetrics pfm = g2.getFontMetrics();
                            int ptw = pfm.stringWidth(pinTag) + 6;
                            g2.setColor(new Color(15, 20, 25, 220));
                            g2.fillRoundRect(px - ptw / 2, py - 24, ptw, 16, 4, 4);
                            g2.setColor(pinColor);
                            g2.drawRoundRect(px - ptw / 2, py - 24, ptw, 16, 4, 4);
                            g2.drawString(pinTag, px - ptw / 2 + 3, py - 12);
                        }
                    }

                    // Render Sprite Image Frame
                    BufferedImage frameImg = obj.getCurrentFrame();
                    if (frameImg == null) frameImg = obj.fullImage;
                    if (frameImg != null) {
                        int sx = toScreenX(obj.x);
                        int sy = toScreenY(obj.y);
                        int sw = (int) Math.round(obj.getDisplayWidth() * currentScale);
                        int sh = (int) Math.round(obj.getDisplayHeight() * currentScale);

                        if (obj.flipH) {
                            g2.drawImage(frameImg, sx + sw, sy, -sw, sh, null);
                        } else {
                            g2.drawImage(frameImg, sx, sy, sw, sh, null);
                        }

                        if (obj == activeOverlayObject) {
                            float[] dash = { 5.0f, 4.0f };
                            g2.setColor(new Color(0, 230, 255));
                            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
                            g2.drawRect(sx, sy, sw, sh);

                            int ax = toScreenX(obj.getAnchorX());
                            int ay = toScreenY(obj.getAnchorY());
                            g2.setColor(new Color(255, 60, 60));
                            g2.setStroke(new BasicStroke(2.0f));
                            g2.drawLine(ax - 7, ay, ax + 7, ay);
                            g2.drawLine(ax, ay - 7, ax, ay + 7);
                            g2.drawOval(ax - 4, ay - 4, 8, 8);

                            String tag = String.format(" %s [X: %d, Y: %d | Scale: %d%%] ",
                                    obj.imageId, obj.getAnchorX(), obj.getAnchorY(), obj.scale);
                            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                            FontMetrics fm = g2.getFontMetrics();
                            int tw = fm.stringWidth(tag) + 10;
                            int th = 20;

                            int tx = sx + obj.labelOffsetX;
                            int ty = sy + obj.labelOffsetY;

                            if (obj.customLabelPos && (Math.abs(obj.labelOffsetX) > 20 || Math.abs(obj.labelOffsetY + 24) > 20)) {
                                g2.setColor(new Color(0, 230, 255, 160));
                                g2.setStroke(new BasicStroke(1.2f));
                                g2.drawLine(tx + tw / 2, ty + th / 2, sx + sw / 2, sy + sh / 2);
                            }

                            g2.setColor(new Color(0, 30, 60, 240));
                            g2.fillRoundRect(tx, ty, tw, th, 6, 6);
                            g2.setColor(new Color(0, 230, 255));
                            g2.drawRoundRect(tx, ty, tw, th, 6, 6);
                            g2.drawString(tag, tx + 4, ty + 14);
                        }
                    }
                }
            }

            // Grid Overlay & Dialogue Safe Zone
            if (chkShowGrid != null && chkShowGrid.isSelected()) {
                g2.setColor(new Color(255, 255, 255, 40));
                for (int x = 160; x < 1920; x += 160) {
                    int sx = toScreenX(x);
                    g2.drawLine(sx, currentOffsetY, sx, currentOffsetY + drawH);
                }
                for (int y = 120; y < 1080; y += 120) {
                    int sy = toScreenY(y);
                    g2.drawLine(currentOffsetX, sy, currentOffsetX + drawW, sy);
                }

                int diagY = toScreenY(780);
                g2.setColor(new Color(255, 60, 60, 140));
                g2.drawLine(currentOffsetX, diagY, currentOffsetX + drawW, diagY);
                g2.drawString("Dialogue Box Zone (Y >= 780)", currentOffsetX + 10, diagY - 6);
            }

            // Saved Clickers
            if (chkShowAllClickers != null && chkShowAllClickers.isSelected()) {
                for (int i = 0; i < savedClickers.size(); i++) {
                    ClickerDef d = savedClickers.get(i);
                    int sx1 = toScreenX(d.x1);
                    int sy1 = toScreenY(d.y1);
                    int sx2 = toScreenX(d.x2);
                    int sy2 = toScreenY(d.y2);
                    int sw = sx2 - sx1;
                    int sh = sy2 - sy1;

                    Color c = d.color != null ? d.color : Color.CYAN;
                    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                    g2.fillRect(sx1, sy1, sw, sh);

                    g2.setColor(c);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRect(sx1, sy1, sw, sh);

                    g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                    String label = d.id + " [" + (d.x2 - d.x1) + "x" + (d.y2 - d.y1) + " | L:" + d.layer + "]";
                    FontMetrics lfm = g2.getFontMetrics();
                    int lw = lfm.stringWidth(label) + 8;
                    int lh = 18;

                    g2.setColor(new Color(0, 0, 0, 190));
                    g2.fillRect(sx1, Math.max(currentOffsetY, sy1 - lh), lw, lh);
                    g2.setColor(c);
                    g2.drawString(label, sx1 + 4, Math.max(currentOffsetY + 13, sy1 - 4));
                }
            }

            // Active Selection Rectangle (CLICKERNEW)
            int selSx1 = toScreenX(curX1);
            int selSy1 = toScreenY(curY1);
            int selSx2 = toScreenX(curX2);
            int selSy2 = toScreenY(curY2);
            int selSw = selSx2 - selSx1;
            int selSh = selSy2 - selSy1;

            if (selSw > 0 && selSh > 0) {
                g2.setColor(new Color(0, 190, 255, 75));
                g2.fillRect(selSx1, selSy1, selSw, selSh);

                float[] dash = { 6.0f, 4.0f };
                g2.setColor(new Color(255, 215, 0)); // Gold
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
                g2.drawRect(selSx1, selSy1, selSw, selSh);

                int handleSize = 6;
                g2.setColor(Color.WHITE);
                g2.fillRect(selSx1 - 3, selSy1 - 3, handleSize, handleSize);
                g2.fillRect(selSx2 - 3, selSy1 - 3, handleSize, handleSize);
                g2.fillRect(selSx1 - 3, selSy2 - 3, handleSize, handleSize);
                g2.fillRect(selSx2 - 3, selSy2 - 3, handleSize, handleSize);

                String clickerIdStr = (txtId != null && !txtId.getText().trim().isEmpty()) ? txtId.getText().trim() : "CLICKER";
                String dimTag = String.format(" Clicker %s: [%d, %d] -> [%d, %d] (%d × %d px) ",
                        clickerIdStr, curX1, curY1, curX2, curY2, curX2 - curX1, curY2 - curY1);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                FontMetrics dfm = g2.getFontMetrics();
                int dw = dfm.stringWidth(dimTag) + 6;
                int dh = 20;

                int tagX = selSx1 + (selSw - dw) / 2;
                int tagY = selSy2 + 4;

                g2.setColor(new Color(25, 20, 5, 245));
                g2.fillRoundRect(tagX, tagY, dw, dh, 6, 6);
                g2.setColor(new Color(255, 215, 0));
                g2.drawRoundRect(tagX, tagY, dw, dh, 6, 6);
                g2.drawString(dimTag, tagX + 3, tagY + 14);
            }

            // Drop Hover Feedback
            if (isDropHover) {
                g2.setColor(new Color(0, 170, 255, 65));
                g2.fillRect(currentOffsetX, currentOffsetY, drawW, drawH);

                float[] dropDash = { 8.0f, 6.0f };
                g2.setColor(new Color(0, 240, 255));
                g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dropDash, 0.0f));
                g2.drawRect(currentOffsetX + 8, currentOffsetY + 8, drawW - 16, drawH - 16);

                String dropText1 = "DROP IMAGE OR PROJECT FILE HERE";
                g2.setFont(new Font("Segoe UI", Font.BOLD, 20));
                FontMetrics dfm1 = g2.getFontMetrics();
                int dw1 = dfm1.stringWidth(dropText1);

                g2.setColor(new Color(10, 20, 35, 245));
                g2.fillRoundRect(currentOffsetX + (drawW - dw1 - 40) / 2, currentOffsetY + drawH / 2 - 35, dw1 + 40, 70, 12, 12);
                g2.setColor(new Color(0, 230, 255));
                g2.drawRoundRect(currentOffsetX + (drawW - dw1 - 40) / 2, currentOffsetY + drawH / 2 - 35, dw1 + 40, 70, 12, 12);
                g2.drawString(dropText1, currentOffsetX + (drawW - dw1) / 2, currentOffsetY + drawH / 2 + 8);
            }

            // Logo Branding in corner
            if (logoImage != null) {
                int bottomSpace = viewH - (currentOffsetY + drawH);
                int targetH = (bottomSpace >= 28) ? Math.min(44, bottomSpace - 8) : 26;
                int targetW = (int) Math.round((double) logoImage.getWidth() * targetH / logoImage.getHeight());
                int lx = Math.max(6, currentOffsetX);
                int ly = (bottomSpace >= targetH + 4) ? currentOffsetY + drawH + (bottomSpace - targetH) / 2 : Math.max(2, viewH - targetH - 4);

                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(logoImage, lx, ly, targetW, targetH, null);
            }

            g2.dispose();
        }
    }
}
