package com.vscodeandroid.editor;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EditorViewModel extends ViewModel {

    private final MutableLiveData<List<File>> openFiles = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<File> currentDirectory = new MutableLiveData<>();
    private final MutableLiveData<Boolean> terminalRequested = new MutableLiveData<>(false);
    private final MutableLiveData<String> activeFilePath = new MutableLiveData<>();

    public LiveData<List<File>> getOpenFiles() { return openFiles; }
    public LiveData<File> getCurrentDirectory() { return currentDirectory; }
    public LiveData<Boolean> getTerminalRequested() { return terminalRequested; }
    public LiveData<String> getActiveFilePath() { return activeFilePath; }

    public void openFile(File file) {
        List<File> current = openFiles.getValue();
        if (current == null) current = new ArrayList<>();
        if (!current.contains(file)) {
            current.add(file);
            openFiles.setValue(current);
        }
        activeFilePath.setValue(file.getAbsolutePath());
    }

    public void closeFile(File file) {
        List<File> current = openFiles.getValue();
        if (current != null) {
            current.remove(file);
            openFiles.setValue(current);
        }
    }

    public void setCurrentDirectory(File dir) {
        currentDirectory.setValue(dir);
    }

    public void requestTerminal() {
        terminalRequested.setValue(true);
    }

    public void clearTerminalRequest() {
        terminalRequested.setValue(false);
    }
}
