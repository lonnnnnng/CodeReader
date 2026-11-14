import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * 从同一套书页与代码括号几何生成 Android 图标和品牌预览，避免方形、圆形和主题图标各自漂移。
 * 只使用 JDK 标准库，Android 项目已有的 Java 17 即可重建全部图标资源。
 *
 * @author long
 */
public final class GenerateIcons {
    private static final String BACKGROUND = "#063C30";
    private static final String COVER = "M30 33 Q43 30 54 37 Q65 30 78 33 L78 71 Q65 68 54 76 Q43 68 30 71 Z";
    private static final String LEFT_PAGE = "M30 33 Q43 30 53 37 L53 72 Q43 65 30 68 Z";
    private static final String RIGHT_PAGE = "M55 37 Q65 30 78 33 L78 68 Q65 65 55 72 Z";
    private static final String BOOKMARK = "M51 29 Q54 27 57 29 L57 43 L54 40 L51 43 Z";
    private static final String BRACKETS = "M43 43 L46 46 L39 53 L46 60 L43 63 L33 53 Z M65 43 L62 46 L69 53 L62 60 L65 63 L75 53 Z";
    private static final String SPINE = "M53 37 L55 37 L55 72 L53 72 Z";
    private static final List<Shape> SHAPES = List.of(
        new Shape(COVER, "#309976"),
        new Shape(LEFT_PAGE, "#79D8B8"),
        new Shape(RIGHT_PAGE, "#F7FAF0"),
        new Shape(BOOKMARK, "#E8C474"),
        new Shape(BRACKETS, BACKGROUND)
    );

    private record Shape(String path, String color) {}

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path resources = root.resolve("app/src/main/res");
        if (!Files.isDirectory(resources)) throw new IllegalStateException("请在 CodeReader 项目根目录运行");

        write(resources.resolve("values/ic_launcher_colors.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <color name=\"ic_launcher_background\">" + BACKGROUND + "</color>\n</resources>\n");
        write(resources.resolve("drawable/ic_launcher_foreground.xml"), vector(108, paths(false), false));
        write(resources.resolve("drawable/ic_launcher_monochrome.xml"), vector(108, monochromePaths(), false));
        for (boolean round : new boolean[] {false, true}) {
            String name = round ? "ic_launcher_round" : "ic_launcher";
            String backgroundPath = round
                ? "M36 0 A36 36 0 1 1 36 72 A36 36 0 1 1 36 0 Z"
                : "M16 0 L56 0 Q72 0 72 16 L72 56 Q72 72 56 72 L16 72 Q0 72 0 56 L0 16 Q0 0 16 0 Z";
            String legacy = "    <path android:fillColor=\"" + BACKGROUND + "\" android:pathData=\"" + backgroundPath + "\" />\n"
                + "    <group android:translateX=\"-18\" android:translateY=\"-18\">\n" + paths(false) + "    </group>\n";
            write(resources.resolve("mipmap-anydpi/" + name + ".xml"), vector(72, legacy, true));
            write(resources.resolve("mipmap-anydpi-v26/" + name + ".xml"), adaptive(true));
            write(resources.resolve("mipmap-anydpi-v33/" + name + ".xml"), adaptive(true));
        }

        Path branding = root.resolve("branding");
        Files.createDirectories(branding);
        write(branding.resolve("icon.svg"), svg(false, false));
        write(branding.resolve("icon-round.svg"), svg(true, false));
        write(branding.resolve("icon-monochrome.svg"), svg(true, true));
        ImageIO.write(render(1024, false, false), "png", branding.resolve("icon.png").toFile());
        ImageIO.write(render(1024, true, false), "png", branding.resolve("icon-round.png").toFile());
        ImageIO.write(render(1024, true, true), "png", branding.resolve("icon-monochrome.png").toFile());
        preview(branding.resolve("icon-preview.png"));
        System.out.println("已生成方形、圆形、自适应和单色图标，以及 branding/ 品牌预览。");
    }

    private static String paths(boolean monochrome) {
        StringBuilder result = new StringBuilder();
        for (Shape shape : SHAPES) {
            result.append("    <path android:fillColor=\"").append(monochrome ? "#FFFFFF" : shape.color())
                .append("\" android:pathData=\"").append(shape.path()).append("\" />\n");
        }
        return result.toString();
    }

    private static String monochromePaths() {
        // 括号和书脊使用透明留空，系统统一染色后仍然可以辨认“代码 + 阅读”的主体。 @author long
        return "    <path android:fillColor=\"#FFFFFF\" android:fillType=\"evenOdd\" android:pathData=\""
            + COVER + " " + BRACKETS + " " + SPINE + "\" />\n"
            + "    <path android:fillColor=\"#FFFFFF\" android:pathData=\"" + BOOKMARK + "\" />\n";
    }

    private static String vector(int viewport, String paths, boolean legacy) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- long: " + (legacy ? "旧系统直接显示完整图标，主体按自适应图标的可见窗口等比例映射。" : "108dp 图层保留外侧裁切空间，书页、代码括号与书签均位于中央安全区域。") + " -->\n"
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:width=\"" + (legacy ? "48" : "108") + "dp\" android:height=\"" + (legacy ? "48" : "108") + "dp\"\n"
            + "    android:viewportWidth=\"" + viewport + "\" android:viewportHeight=\"" + viewport + "\">\n" + paths + "</vector>\n";
    }

    private static String adaptive(boolean monochrome) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- long: 背景独立铺满系统遮罩，前景不预裁切，圆形和方形桌面可以使用同一主体。 -->\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@color/ic_launcher_background\" />\n"
            + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
            + (monochrome ? "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n" : "")
            + "</adaptive-icon>\n";
    }

    private static String svg(boolean round, boolean monochrome) {
        StringBuilder result = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"18 18 72 72\" role=\"img\" aria-label=\"CodeReader\">\n");
        result.append("<title>CodeReader — code pages and bookmark</title>\n");
        String background = monochrome ? "#C6F5E4" : BACKGROUND;
        result.append(round ? "<circle cx=\"54\" cy=\"54\" r=\"36\" fill=\"" : "<rect x=\"18\" y=\"18\" width=\"72\" height=\"72\" rx=\"16\" fill=\"")
            .append(background).append("\"/>\n");
        if (monochrome) {
            result.append("<path fill=\"").append(BACKGROUND).append("\" fill-rule=\"evenodd\" d=\"")
                .append(COVER).append(" ").append(BRACKETS).append(" ").append(SPINE).append("\"/>\n")
                .append("<path fill=\"").append(BACKGROUND).append("\" d=\"").append(BOOKMARK).append("\"/>\n");
        } else {
            for (Shape shape : SHAPES) result.append("<path fill=\"").append(shape.color()).append("\" d=\"").append(shape.path()).append("\"/>\n");
        }
        return result.append("</svg>\n").toString();
    }

    private static BufferedImage render(int size, boolean round, boolean monochrome) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(size / 72.0, size / 72.0);
        graphics.translate(-18, -18);
        graphics.setColor(Color.decode(monochrome ? "#C6F5E4" : BACKGROUND));
        graphics.fill(round ? new Ellipse2D.Double(18, 18, 72, 72) : new RoundRectangle2D.Double(18, 18, 72, 72, 32, 32));
        if (monochrome) {
            Area silhouette = new Area(path(COVER));
            silhouette.subtract(new Area(path(BRACKETS)));
            silhouette.subtract(new Area(path(SPINE)));
            silhouette.add(new Area(path(BOOKMARK)));
            graphics.setColor(Color.decode(BACKGROUND));
            graphics.fill(silhouette);
        } else {
            for (Shape shape : SHAPES) {
                graphics.setColor(Color.decode(shape.color()));
                graphics.fill(path(shape.path()));
            }
        }
        graphics.dispose();
        return image;
    }

    private static Path2D path(String data) {
        Matcher matcher = Pattern.compile("[MLQZ]|-?\\d+(?:\\.\\d+)?").matcher(data);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) tokens.add(matcher.group());
        Path2D path = new Path2D.Double();
        for (int index = 0; index < tokens.size();) {
            String command = tokens.get(index++);
            switch (command) {
                case "M" -> { path.moveTo(number(tokens, index), number(tokens, index + 1)); index += 2; }
                case "L" -> { path.lineTo(number(tokens, index), number(tokens, index + 1)); index += 2; }
                case "Q" -> { path.quadTo(number(tokens, index), number(tokens, index + 1), number(tokens, index + 2), number(tokens, index + 3)); index += 4; }
                case "Z" -> path.closePath();
                default -> throw new IllegalArgumentException("无法识别图标路径指令：" + command);
            }
        }
        return path;
    }

    private static double number(List<String> tokens, int index) { return Double.parseDouble(tokens.get(index)); }

    private static void preview(Path output) throws Exception {
        BufferedImage board = new BufferedImage(1104, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = board.createGraphics();
        graphics.setColor(Color.decode("#F4F7F8"));
        graphics.fillRect(0, 0, board.getWidth(), board.getHeight());
        for (int index = 0; index < 3; index++) {
            graphics.drawImage(render(272, index > 0, index == 2), 48 + index * 352, 40, null);
            // 桌面图标实际显示很小，预览同时保留 48px 版本，便于发现细节拥挤或括号难以识别。 @author long
            graphics.drawImage(render(48, index > 0, index == 2), 160 + index * 352, 334, null);
        }
        graphics.dispose();
        ImageIO.write(board, "png", output.toFile());
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
