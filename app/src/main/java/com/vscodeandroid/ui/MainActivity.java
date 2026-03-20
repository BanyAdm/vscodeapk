package com.vscodeandroid.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.vscodeandroid.R;
import com.vscodeandroid.bridge.JavascriptBridge;
import com.vscodeandroid.bridge.TermuxBridge;
import com.vscodeandroid.databinding.ActivityMainBinding;
import com.vscodeandroid.editor.EditorViewModel;
import com.vscodeandroid.editor.VSCodeWebView;
import com.vscodeandroid.filesystem.FileExplorerFragment;
import com.vscodeandroid.terminal.TerminalFragment;
import com.vscodeandroid.terminal.TerminalSessionService;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private EditorViewModel viewModel;
    private VSCodeWebView vsCodeWebView;
    private TerminalFragment terminalFragment;
    private TermuxBridge termuxBridge;
    private BottomSheetBehavior<View> terminalSheetBehavior;
    private boolean terminalVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(EditorViewModel.class);
        termuxBridge = new TermuxBridge(this);

        // Check if Termux is installed, if not launch setup
        if (!termuxBridge.isTermuxInstalled()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        setupToolbar();
        setupDrawer();
        setupVSCodeWebView();
        setupTerminalPanel();
        setupTabBar();
        setupObservers();
        startTerminalService();
        handleIncomingIntent(getIntent());
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
    }

    private void setupDrawer() {
        // File explorer in left drawer
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(R.id.file_explorer_container) == null) {
            fm.beginTransaction()
                    .replace(R.id.file_explorer_container, new FileExplorerFragment())
                    .commit();
        }

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar,
                R.string.drawer_open, R.string.drawer_close);
        binding.drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // Default: show sidebar on tablets, hide on phones
        if (getResources().getBoolean(R.bool.is_tablet)) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN);
        }
    }

    private void setupVSCodeWebView() {
        vsCodeWebView = binding.vscodeWebview;
        vsCodeWebView.initialize(this, new JavascriptBridge(this, this));
    }

    private void setupTerminalPanel() {
        terminalSheetBehavior = BottomSheetBehavior.from(binding.terminalPanel);
        terminalSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        terminalSheetBehavior.setPeekHeight(300);

        terminalFragment = new TerminalFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.terminal_container, terminalFragment)
                .commit();

        binding.btnCloseTerminal.setOnClickListener(v -> hideTerminal());
        binding.btnNewTerminal.setOnClickListener(v -> openNewTerminal());
        binding.btnMaximizeTerminal.setOnClickListener(v -> toggleTerminalMaximize());
    }

    private void setupTabBar() {
        binding.editorTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getTag() instanceof File) {
                    vsCodeWebView.openFile((File) tab.getTag());
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupObservers() {
        viewModel.getOpenFiles().observe(this, files -> {
            binding.editorTabLayout.removeAllTabs();
            for (File f : files) {
                TabLayout.Tab tab = binding.editorTabLayout.newTab();
                tab.setText(f.getName());
                tab.setTag(f);
                binding.editorTabLayout.addTab(tab);
            }
        });

        viewModel.getTerminalRequested().observe(this, requested -> {
            if (Boolean.TRUE.equals(requested)) {
                showTerminal();
                viewModel.clearTerminalRequest();
            }
        });

        viewModel.getCurrentDirectory().observe(this, dir -> {
            if (dir != null) {
                vsCodeWebView.setWorkspaceFolder(dir.getAbsolutePath());
            }
        });
    }

    // Called from JavascriptBridge when VS Code requests a new terminal
    public void showTerminal() {
        terminalVisible = true;
        terminalSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        if (!terminalFragment.hasSession()) {
            terminalFragment.createNewSession();
        }
    }

    public void hideTerminal() {
        terminalVisible = false;
        terminalSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
    }

    public void openNewTerminal() {
        showTerminal();
        terminalFragment.createNewSession();
    }

    private void toggleTerminalMaximize() {
        if (terminalSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            terminalSheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        } else {
            terminalSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    public void openFileInEditor(File file) {
        viewModel.openFile(file);
        vsCodeWebView.openFile(file);
    }

    public void openFolderInExplorer(File folder) {
        viewModel.setCurrentDirectory(folder);
    }

    private void startTerminalService() {
        Intent serviceIntent = new Intent(this, TerminalSessionService.class);
        startForegroundService(serviceIntent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                File file = new File(data.getPath());
                if (file.exists()) {
                    openFileInEditor(file);
                }
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Forward keyboard shortcuts to VS Code WebView
        if (vsCodeWebView.handleKeyEvent(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_open_folder) {
            openFolderPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                // Convert URI to path and open in explorer
                String path = treeUri.getPath();
                openFolderInExplorer(new File(path));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        vsCodeWebView.destroy();
        termuxBridge.disconnect();
    }

    @Override
    public void onBackPressed() {
        if (terminalVisible) {
            hideTerminal();
        } else if (binding.drawerLayout.isOpen()) {
            binding.drawerLayout.close();
        } else {
            super.onBackPressed();
        }
    }
}
