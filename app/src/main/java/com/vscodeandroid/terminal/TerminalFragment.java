package com.vscodeandroid.terminal;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vscodeandroid.R;
import com.vscodeandroid.bridge.TermuxBridge;

import java.util.ArrayList;
import java.util.List;

public class TerminalFragment extends Fragment {

    private TextView terminalOutput;
    private EditText terminalInput;
    private ScrollView scrollView;
    private TermuxBridge termuxBridge;
    private TermuxBridge.TerminalSocketSession activeSession;
    private final List<TermuxBridge.TerminalSocketSession> sessions = new ArrayList<>();
    private String workingDirectory = TermuxBridge.TERMUX_HOME;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        terminalOutput = view.findViewById(R.id.terminal_output);
        terminalInput = view.findViewById(R.id.terminal_input);
        scrollView = view.findViewById(R.id.terminal_scroll);
        termuxBridge = new TermuxBridge(requireContext());

        terminalInput.setOnEditorActionListener((v, actionId, event) -> {
            String cmd = terminalInput.getText().toString();
            sendInput(cmd + "\n");
            appendOutput("$ " + cmd + "\n");
            terminalInput.setText("");
            return true;
        });

        return view;
    }

    public void createNewSession() {
        if (!termuxBridge.isTermuxInstalled()) {
            appendOutput("Termux not installed. Please install from GitHub.\n");
            return;
        }
        activeSession = termuxBridge.openTerminalSession(workingDirectory);
        activeSession.connect(
            output -> {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> appendOutput(output));
            },
            () -> {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> appendOutput("Connected to Termux\n"));
            },
            error -> {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> {
                        appendOutput("Could not connect. Running setup...\n");
                        termuxBridge.runSetupScript();
                    });
            }
        );
        sessions.add(activeSession);
    }

    public void sendInput(String input) {
        if (activeSession != null && activeSession.isConnected()) {
            activeSession.write(input);
        } else {
            appendOutput("No active session. Creating one...\n");
            createNewSession();
        }
    }

    public void setWorkingDirectory(String dir) {
        this.workingDirectory = dir;
    }

    public boolean hasSession() {
        return !sessions.isEmpty();
    }

    private void appendOutput(String text) {
        if (terminalOutput != null) {
            terminalOutput.append(text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (activeSession != null) activeSession.disconnect();
    }
}
