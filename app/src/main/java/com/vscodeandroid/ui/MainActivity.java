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
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.vscodeandroid.R;
import com.vscodeandroid.bridge.JavascriptBridge;
import com.vscodeandroid.bridge.TermuxBridge;
import com.vscodeandroid.editor.EditorViewModel;
import com.vscodeandroid.editor.VSCodeWebView;
import com.vscodeandroid.filesystem.FileExplorerFragment;
import com.vscodeandroid.terminal.TerminalFragment;
import com.vscodeandroid.terminal.TerminalSessionService;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    public TerminalFragment terminalFragment;
    public VSCodeWebView vsCodeWebView;
    private EditorViewModel viewModel;
    private DrawerLayout drawerLayout;
    private TabLayout tabLayout;
    private TermuxBridge termuxBridge;
    private BottomSheetBehavior<View> terminalSheetBehavior;
    private boolean terminalVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(EditorViewModel.class);
        termuxBridge = new TermuxBridge(this);

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
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
    }

    private void setupDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout);
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(R.id.file_explorer_container) == null) {
            fm.beginTransaction()
                    .replace(R.id.file_explorer_container, new FileExplorerFragment())
                    .commit();
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
    }

    private void setupVSCodeWebView() {
        vsCodeWebView = findViewById(R.id.vscode_webview);
        vsCodeWebView.initialize(this, new JavascriptBridge(this, this));
    }

    private void setupTerminalPanel() {
        View terminalPanel = findViewById(R.id.terminal_panel);
        terminalSheetBehavior = BottomSheetBehavior.from(terminalPanel);
        terminalSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        terminalSheetBehavior.setPeekHeight(300);

        terminalFragment = new TerminalFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.terminal_container, terminalFragment)
                .commit();

        findViewById(R.id.btn_close_terminal).setOnClickListener(v -> hideTerminal());
        findViewById(R.id.btn_new_terminal).setOnClickListener(v -> openNewTerminal());
        findViewById(R.id.btn_maximize_terminal).setOnClickListener(v -> toggleTerminalMaximize());
    }

    private void setupTabBar() {
        tabLayout = findViewById(R.id.editor_tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
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
            tabLayout.removeAllTabs();
            for (File f : files) {
                TabLayout.Tab tab = tabLayout.newTab();
                tab.setText(f.getName());
                tab.setTag(f);
                tabLayout.addTab(tab);
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

    public TerminalFragment getTerminalFragment() { return terminalFragment; }
    public VSCodeWebView getVsCodeWebView() { return vsCodeWebView; }

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
                if (file.exists()) openFileInEditor(file);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (vsCodeWebView.handleKeyEvent(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
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
                openFolderInExplorer(new File(treeUri.getPath()));
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
        } else if (drawerLayout.isOpen()) {
            drawerLayout.close();
        } else {
            super.onBackPressed();
        }
    }
}
