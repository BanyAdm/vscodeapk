package com.vscodeandroid.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.vscodeandroid.R;
import com.vscodeandroid.bridge.TermuxBridge;

public class SetupActivity extends AppCompatActivity {

    private TermuxBridge termuxBridge;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button actionButton;
    private int setupStep = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        termuxBridge = new TermuxBridge(this);
        statusText = findViewById(R.id.setup_status);
        progressBar = findViewById(R.id.setup_progress);
        actionButton = findViewById(R.id.setup_action_button);

        checkSetupState();
    }

    private void checkSetupState() {
        if (!termuxBridge.isTermuxInstalled()) {
            // Step 1: Need to install Termux
            showStep1InstallTermux();
        } else if (!termuxBridge.isTermuxSetupComplete()) {
            // Step 2: Termux installed but bridge not set up
            showStep2SetupBridge();
        } else {
            // All done, go to main app
            launchMainActivity();
        }
    }

    private void showStep1InstallTermux() {
        setupStep = 1;
        statusText.setText(
            "Termux is required to power the terminal, file system, package manager, and all " +
            "developer tools.\n\n" +
            "Install Termux from GitHub (the F-Droid/GitHub version, NOT Play Store).\n\n" +
            "After installing, come back and tap Continue."
        );
        progressBar.setProgress(25);
        actionButton.setText("Download Termux from GitHub");
        actionButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/termux/termux-app/releases/latest"));
            startActivity(intent);
            // Change button to "I've installed it"
            actionButton.setText("I've installed Termux → Continue");
            actionButton.setOnClickListener(v2 -> checkSetupState());
        });
    }

    private void showStep2SetupBridge() {
        setupStep = 2;
        statusText.setText(
            "Great! Termux is installed.\n\n" +
            "Now we need to set up the bridge that connects this app to Termux.\n\n" +
            "Tap the button below. This will open Termux and run a one-time setup script that:\n" +
            "  • Installs required packages (socat, openssh, nodejs, git)\n" +
            "  • Sets up the socket bridge for the embedded terminal\n" +
            "  • Configures file system access\n\n" +
            "After the script finishes, come back here and tap 'Done'."
        );
        progressBar.setProgress(60);
        actionButton.setText("Run Setup in Termux");
        actionButton.setOnClickListener(v -> {
            termuxBridge.runSetupScript();
            actionButton.setText("Setup is done → Continue");
            actionButton.setOnClickListener(v2 -> {
                progressBar.setProgress(90);
                statusText.setText("Verifying connection to Termux bridge...");
                actionButton.setEnabled(false);
                termuxBridge.testConnection(
                    success -> runOnUiThread(() -> {
                        if (success) {
                            termuxBridge.markSetupComplete();
                            launchMainActivity();
                        } else {
                            statusText.setText(
                                "Couldn't connect to Termux bridge.\n\n" +
                                "Make sure the setup script finished successfully in Termux, " +
                                "then try again."
                            );
                            actionButton.setEnabled(true);
                            actionButton.setText("Retry");
                            actionButton.setOnClickListener(v3 -> checkSetupState());
                        }
                    }),
                    error -> runOnUiThread(() -> {
                        statusText.setText("Error: " + error.getMessage());
                        actionButton.setEnabled(true);
                    })
                );
            });
        });
    }

    private void launchMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
