package com.vscodeandroid.filesystem;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vscodeandroid.R;
import com.vscodeandroid.bridge.TermuxBridge;
import com.vscodeandroid.ui.MainActivity;

import java.io.File;

public class FileExplorerFragment extends Fragment {

    private RecyclerView fileTree;
    private FileTreeAdapter adapter;
    private TextView currentPathText;
    private File currentRoot;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_file_explorer, container, false);

        fileTree = view.findViewById(R.id.file_tree);
        currentPathText = view.findViewById(R.id.current_path_text);

        ImageButton btnHome = view.findViewById(R.id.btn_explorer_home);
        ImageButton btnNewFile = view.findViewById(R.id.btn_new_file);
        ImageButton btnNewFolder = view.findViewById(R.id.btn_new_folder);
        ImageButton btnRefresh = view.findViewById(R.id.btn_refresh);

        // Start in Termux home
        currentRoot = new File(TermuxBridge.TERMUX_HOME);
        setupRecyclerView();
        loadDirectory(currentRoot);

        btnHome.setOnClickListener(v -> loadDirectory(new File(TermuxBridge.TERMUX_HOME)));
        btnNewFile.setOnClickListener(v -> showNewFileDialog());
        btnNewFolder.setOnClickListener(v -> showNewFolderDialog());
        btnRefresh.setOnClickListener(v -> loadDirectory(currentRoot));

        return view;
    }

    private void setupRecyclerView() {
        adapter = new FileTreeAdapter(file -> {
            if (file.isDirectory()) {
                loadDirectory(file);
            } else {
                openFileInEditor(file);
            }
        }, file -> {
            // Long press = context menu
            showFileContextMenu(file);
        });
        fileTree.setLayoutManager(new LinearLayoutManager(requireContext()));
        fileTree.setAdapter(adapter);
    }

    private void loadDirectory(File dir) {
        currentRoot = dir;
        currentPathText.setText(dir.getAbsolutePath()
            .replace(TermuxBridge.TERMUX_HOME, "~"));
        adapter.setFiles(dir.listFiles());
    }

    private void openFileInEditor(File file) {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openFileInEditor(file);
        }
    }

    private void showNewFileDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("New File");
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("filename.txt");
        builder.setView(input);
        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                File newFile = new File(currentRoot, name);
                try {
                    newFile.createNewFile();
                    loadDirectory(currentRoot);
                    openFileInEditor(newFile);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showNewFolderDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("New Folder");
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("folder-name");
        builder.setView(input);
        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                new File(currentRoot, name).mkdirs();
                loadDirectory(currentRoot);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showFileContextMenu(File file) {
        String[] options = {"Open", "Rename", "Delete", "Copy Path"};
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(file.getName())
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: openFileInEditor(file); break;
                    case 1: showRenameDialog(file); break;
                    case 2: confirmDelete(file); break;
                    case 3: copyPathToClipboard(file); break;
                }
            }).show();
    }

    private void showRenameDialog(File file) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("Rename");
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setText(file.getName());
        builder.setView(input);
        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                file.renameTo(new File(file.getParent(), newName));
                loadDirectory(currentRoot);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void confirmDelete(File file) {
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete " + file.getName() + "?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                deleteRecursive(file);
                loadDirectory(currentRoot);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        file.delete();
    }

    private void copyPathToClipboard(File file) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", file.getAbsolutePath()));
    }
}
