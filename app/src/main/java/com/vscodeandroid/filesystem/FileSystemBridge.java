package com.vscodeandroid.filesystem;

import android.content.Context;

import com.vscodeandroid.bridge.TermuxBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Handles all file system operations for VS Code.
 * Reads/writes files directly on the Android filesystem,
 * which includes Termux's home directory and any mounted storage.
 */
public class FileSystemBridge {

    private final Context context;
    private final TermuxBridge termuxBridge;

    public FileSystemBridge(Context context, TermuxBridge termuxBridge) {
        this.context = context;
        this.termuxBridge = termuxBridge;
    }

    public String readFile(String path) {
        try {
            File file = new File(resolvePath(path));
            if (!file.exists()) return null;
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // For binary files or permission issues, use Termux bridge
            return termuxBridge.executeCommandSync("cat '" + escapePath(path) + "'", null);
        }
    }

    public void writeFile(String path, String content) {
        try {
            File file = new File(resolvePath(path));
            file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Fallback to tee via Termux for paths we don't have direct access to
            String escaped = content.replace("'", "'\\''");
            termuxBridge.executeCommandSync(
                "printf '%s' '" + escaped + "' > '" + escapePath(path) + "'", null);
        }
    }

    public void deleteFile(String path) {
        File file = new File(resolvePath(path));
        if (file.exists()) {
            if (file.isDirectory()) {
                deleteRecursively(file);
            } else {
                file.delete();
            }
        }
    }

    public void createDirectory(String path) {
        new File(resolvePath(path)).mkdirs();
    }

    /**
     * Returns JSON array: [{name: "file.txt", type: "file"}, {name: "dir", type: "directory"}]
     */
    public String readDirectory(String path) {
        try {
            File dir = new File(resolvePath(path));
            if (!dir.exists() || !dir.isDirectory()) return "[]";

            File[] files = dir.listFiles();
            if (files == null) return "[]";

            JSONArray arr = new JSONArray();
            for (File f : files) {
                JSONObject obj = new JSONObject();
                obj.put("name", f.getName());
                obj.put("type", f.isDirectory() ? "directory" : "file");
                obj.put("size", f.isFile() ? f.length() : 0);
                obj.put("mtime", f.lastModified());
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public boolean fileExists(String path) {
        return new File(resolvePath(path)).exists();
    }

    /**
     * Returns JSON: {size, mtime, type}
     */
    public String stat(String path) {
        try {
            File file = new File(resolvePath(path));
            JSONObject obj = new JSONObject();
            obj.put("size", file.length());
            obj.put("mtime", file.lastModified());
            obj.put("type", file.isDirectory() ? "directory" : "file");
            obj.put("exists", file.exists());
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public void rename(String oldPath, String newPath) {
        File src = new File(resolvePath(oldPath));
        File dst = new File(resolvePath(newPath));
        dst.getParentFile().mkdirs();
        src.renameTo(dst);
    }

    public void copy(String source, String destination) {
        try {
            File src = new File(resolvePath(source));
            File dst = new File(resolvePath(destination));
            dst.getParentFile().mkdirs();
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            termuxBridge.executeCommandSync(
                "cp -r '" + escapePath(source) + "' '" + escapePath(destination) + "'", null);
        }
    }

    /**
     * Normalize path - handle ~ and relative paths
     */
    private String resolvePath(String path) {
        if (path == null) return TermuxBridge.TERMUX_HOME;
        if (path.startsWith("~")) {
            return TermuxBridge.TERMUX_HOME + path.substring(1);
        }
        if (!path.startsWith("/")) {
            return TermuxBridge.TERMUX_HOME + "/" + path;
        }
        return path;
    }

    private String escapePath(String path) {
        return path.replace("'", "'\\''");
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
