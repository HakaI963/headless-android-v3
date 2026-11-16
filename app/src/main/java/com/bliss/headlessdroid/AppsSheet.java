package com.bliss.headlessdroid;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app picker: every launchable app on the phone, one dense list.
 *
 * <p>Nothing here clones or copies an app. The list is read straight from the
 * PackageManager, and launching hands the package straight to the engine.
 */
final class AppsSheet {
    private AppsSheet() {
    }

    static void open(Activity activity, Engine engine) {
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(R.layout.apps);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setCancelable(true);

        ListView list = dialog.findViewById(R.id.apps_list);
        TextView empty = dialog.findViewById(R.id.apps_empty);
        EditText search = dialog.findViewById(R.id.apps_search);
        list.setEmptyView(empty);

        final Adapter adapter = new Adapter(engine.apps());
        list.setAdapter(adapter);
        // The installed-app list is built off the UI thread; refresh the sheet
        // when it lands instead of showing an empty grid of apps.
        final Runnable reload = () -> {
            adapter.replace(engine.apps());
            adapter.filter(String.valueOf(search.getText()));
        };
        engine.addListener(reload);
        dialog.setOnDismissListener(d -> engine.removeListener(reload));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(String.valueOf(s));
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            Engine.AppEntry entry = adapter.item(position);
            dialog.dismiss();
            Tile existing = engine.tileForPkg(entry.pkg);
            if (existing != null) {
                engine.restart(existing.slot);
                return;
            }
            if (engine.freeSlots() <= 0) {
                android.widget.Toast.makeText(activity, "Grid is full (9 apps)",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            engine.launch(entry.pkg);
        });

        dialog.show();
    }

    private static final class Adapter extends BaseAdapter {
        private final List<Engine.AppEntry> all;
        private List<Engine.AppEntry> shown;

        Adapter(List<Engine.AppEntry> entries) {
            this.all = new ArrayList<>(entries);
            this.shown = new ArrayList<>(entries);
        }

        void replace(List<Engine.AppEntry> entries) {
            all.clear();
            all.addAll(entries);
        }

        void filter(String query) {
            String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
            shown = new ArrayList<>();
            for (Engine.AppEntry entry : all) {
                if (needle.isEmpty()
                        || entry.label.toLowerCase(Locale.US).contains(needle)
                        || entry.pkg.toLowerCase(Locale.US).contains(needle)) {
                    shown.add(entry);
                }
            }
            notifyDataSetChanged();
        }

        Engine.AppEntry item(int position) {
            return shown.get(position);
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View view = convert;
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.row_app, parent, false);
            }
            Engine.AppEntry entry = shown.get(position);
            TextView label = view.findViewById(R.id.row_label);
            TextView pkg = view.findViewById(R.id.row_package);
            label.setText(entry.label);
            pkg.setText(entry.pkg);
            return view;
        }
    }
}