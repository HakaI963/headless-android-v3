package com.bliss.headlessdroid;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings, in one column of panels.
 *
 * <p>Everything here is a decision the user makes once: where the AI connects,
 * how the grid is laid out, and how much screen the phone keeps holding for it.
 */
public final class SettingsActivity extends Activity {
    private Engine engine;
    private EditText port;
    private TextView endpoint;
    private TextView token;
    private TextView version;
    private LinearLayout columns;
    private LinearLayout resolutions;
    private Switch wake;
    private boolean editing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);
        engine = Engine.get();
        port = findViewById(R.id.mcp_port);
        endpoint = findViewById(R.id.mcp_endpoint);
        token = findViewById(R.id.mcp_token);
        version = findViewById(R.id.version);
        columns = findViewById(R.id.columns);
        resolutions = findViewById(R.id.resolutions);
        wake = findViewById(R.id.wake_switch);

        String endpointText = engine == null ? "" : engine.mcpEndpoint();
        if (endpointText == null || endpointText.isEmpty()) {
            endpointText = "http://" + Network.host(this) + ":" + Prefs.port(this);
        }
        endpoint.setText(endpointText + "/mcp");
        token.setText(Prefs.token(this));
        version.setText("1.0.0  ·  " + android.os.Build.MODEL
                + "  ·  Android " + android.os.Build.VERSION.RELEASE);

        port.setText(String.valueOf(Prefs.port(this)));
        port.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                editing = true;
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        findViewById(R.id.mcp_port_save).setOnClickListener(v -> applyPort());
        findViewById(R.id.mcp_regen).setOnClickListener(v -> {
            Prefs.newToken(this);
            token.setText(Prefs.token(this));
            engine.restartMcp();
            toast("New token generated");
        });

        wake.setChecked(Prefs.keepAwake(this));
        wake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Prefs.keepAwake(SettingsActivity.this, checked);
                engine.refreshWake();
            }
        });

        findViewById(R.id.relaunch).setOnClickListener(v -> {
            engine.relaunchAll();
            finish();
        });
        findViewById(R.id.close_all).setOnClickListener(v -> {
            engine.closeAll();
            finish();
        });

        select(columns, engine == null ? 3 : engine.columns());
        select(resolutions, Prefs.screen(this));
    }

    private void applyPort() {
        String raw = port.getText() == null ? "" : port.getText().toString().trim();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (Throwable e) {
            value = Droid.DEFAULT_PORT;
        }
        Prefs.port(this, value);
        if (engine != null) {
            engine.restartMcp();
        }
        endpoint.setText("http://" + Network.host(this) + ":" + Prefs.port(this) + "/mcp");
        port.setText(String.valueOf(Prefs.port(this)));
        editing = false;
        toast("MCP restarted on " + Prefs.port(this));
    }

    /** Marks the chosen chip with the accent hairline; clears the rest. */
    private void select(LinearLayout group, int value) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            boolean chosen = Integer.parseInt(String.valueOf(child.getTag())) == value;
            child.setBackgroundResource(chosen ? R.drawable.bg_tile_focus : R.drawable.bg_button);
            child.setOnClickListener(v -> choose(group, child));
        }
    }

    private void choose(LinearLayout group, View child) {
        int value = Integer.parseInt(String.valueOf(child.getTag()));
        if (group == columns) {
            engine.setColumns(value);
        } else {
            Prefs.screen(this, value);
            toast("New apps use " + Droid.SCREENS[value][0] + "x" + Droid.SCREENS[value][1]);
        }
        select(group, value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}