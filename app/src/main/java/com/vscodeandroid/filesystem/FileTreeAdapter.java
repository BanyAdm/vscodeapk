package com.vscodeandroid.filesystem;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vscodeandroid.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.FileViewHolder> {

    public interface OnFileClickListener {
        void onClick(File file);
    }

    private List<File> files = new ArrayList<>();
    private final OnFileClickListener clickListener;
    private final OnFileClickListener longClickListener;
    private File selectedFile;

    public FileTreeAdapter(OnFileClickListener click, OnFileClickListener longClick) {
        this.clickListener = click;
        this.longClickListener = longClick;
    }

    public void setFiles(File[] newFiles) {
        files.clear();
        if (newFiles != null) {
            List<File> sorted = new ArrayList<>(Arrays.asList(newFiles));
            // Directories first, then files, both alphabetically
            sorted.sort((a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            // Filter out hidden files by default (can be toggled)
            for (File f : sorted) {
                if (!f.getName().startsWith(".")) files.add(f);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file_tree, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        File file = files.get(position);
        holder.fileName.setText(file.getName());
        holder.fileIcon.setImageResource(getIconForFile(file));
        holder.itemView.setSelected(file.equals(selectedFile));
        holder.itemView.setOnClickListener(v -> {
            selectedFile = file;
            notifyDataSetChanged();
            clickListener.onClick(file);
        });
        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onClick(file);
            return true;
        });
    }

    @Override
    public int getItemCount() { return files.size(); }

    private int getIconForFile(File file) {
        if (file.isDirectory()) return R.drawable.ic_folder;
        String name = file.getName().toLowerCase();
        if (name.endsWith(".java") || name.endsWith(".kt")) return R.drawable.ic_file_java;
        if (name.endsWith(".js") || name.endsWith(".ts")) return R.drawable.ic_file_js;
        if (name.endsWith(".py")) return R.drawable.ic_file_python;
        if (name.endsWith(".html") || name.endsWith(".htm")) return R.drawable.ic_file_html;
        if (name.endsWith(".css") || name.endsWith(".scss")) return R.drawable.ic_file_css;
        if (name.endsWith(".json")) return R.drawable.ic_file_json;
        if (name.endsWith(".md")) return R.drawable.ic_file_markdown;
        if (name.endsWith(".sh")) return R.drawable.ic_file_shell;
        if (name.endsWith(".xml")) return R.drawable.ic_file_xml;
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".webp")) return R.drawable.ic_file_image;
        return R.drawable.ic_file_generic;
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;

        FileViewHolder(View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.file_icon);
            fileName = itemView.findViewById(R.id.file_name);
        }
    }
}
