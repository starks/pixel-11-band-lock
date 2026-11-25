package io.github.starks.pixelnsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class BandDialog {
    private static final Executor RADIO_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pixel-band-controls");
        thread.setDaemon(true);
        return thread;
    });

    static void open(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Pixel 11 Band Lock · SIM 1")
                .setItems(new String[]{"LTE bands", "5G NR bands"}, (dialog, which) ->
                        load(activity, which == 0 ? PixelRadio.Rat.LTE : PixelRadio.Rat.NR))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void load(Activity activity, PixelRadio.Rat rat) {
        PixelRadio radio = new PixelRadio(activity);
        run(activity, "Reading " + rat.label + " bands…", () -> radio.read(rat),
                state -> showBands(activity, radio, state));
    }

    private static void showBands(Activity activity, PixelRadio radio, PixelRadio.State state) {
        ArrayList<Integer> bands = new ArrayList<>(state.supported());
        if (bands.isEmpty()) {
            error(activity, "The modem reported no supported bands.");
            return;
        }
        String[] labels = new String[bands.size()];
        boolean[] checked = new boolean[bands.size()];
        boolean[] known = new boolean[bands.size()];
        for (int i = 0; i < bands.size(); i++) {
            int band = bands.get(i);
            labels[i] = state.rat().prefix + band;
            checked[i] = state.current().primary().contains(band);
            known[i] = state.known().contains(band);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LinearLayout heading = new LinearLayout(builder.getContext());
        heading.setOrientation(LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int side = Math.round(24 * density);
        heading.setPadding(side, Math.round(16 * density), side, 0);

        TextView title = new TextView(builder.getContext());
        title.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        title.setText(state.rat().label + " bands · SIM 1");
        title.setAccessibilityHeading(true);
        heading.addView(title);

        Button bulk = new Button(builder.getContext(), null, android.R.attr.borderlessButtonStyle);
        LinearLayout.LayoutParams buttonLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonLayout.gravity = Gravity.END;
        heading.addView(bulk, buttonLayout);

        builder.setCustomTitle(heading)
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                    checked[which] = selected;
                    updateSelectionControls(bulk, (AlertDialog) dialog, checked, known);
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply lock", null);
        if (state.hasRestore()) {
            builder.setNeutralButton("Restore", (dialog, which) ->
                    run(activity, "Restoring bands…", () -> {
                        radio.restore(state);
                        return radio.read(state.rat());
                    }, restored -> {
                        Toast.makeText(activity, "Original band masks restored", Toast.LENGTH_SHORT).show();
                        showBands(activity, radio, restored);
                    }));
        }
        AlertDialog dialog = builder.show();
        updateSelectionControls(bulk, dialog, checked, known);
        bulk.setOnClickListener(view -> {
            boolean selectKnown = hasUnselectedKnownBand(checked, known);
            for (int i = 0; i < checked.length; i++) {
                checked[i] = selectKnown && known[i];
                dialog.getListView().setItemChecked(i, checked[i]);
            }
            updateSelectionControls(bulk, dialog, checked, known);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            Set<Integer> selection = new TreeSet<>();
            for (int i = 0; i < bands.size(); i++) if (checked[i]) selection.add(bands.get(i));
            dialog.dismiss();
            run(activity, "Applying band lock…", () -> {
                radio.apply(state, selection);
                return radio.read(state.rat());
            }, updated -> {
                Toast.makeText(activity, "Band masks verified", Toast.LENGTH_SHORT).show();
                showBands(activity, radio, updated);
            });
        });
    }

    private static void updateSelectionControls(Button bulk, AlertDialog dialog, boolean[] checked, boolean[] known) {
        int selected = countSelected(checked);
        boolean selectKnown = hasUnselectedKnownBand(checked, known);
        bulk.setText(selectKnown || selected == 0 ? "Select known bands" : "Deselect all");
        bulk.setEnabled(selectKnown || selected > 0);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected > 0);
    }

    private static boolean hasUnselectedKnownBand(boolean[] checked, boolean[] known) {
        for (int i = 0; i < known.length; i++) if (known[i] && !checked[i]) return true;
        return false;
    }

    private static int countSelected(boolean[] checked) {
        int selected = 0;
        for (boolean value : checked) if (value) selected++;
        return selected;
    }

    private interface Action<T> {
        T execute() throws Exception;
    }

    private static <T> void run(Activity activity, String message, Action<T> action, Consumer<T> success) {
        AlertDialog loading = new AlertDialog.Builder(activity)
                .setMessage(message)
                .setCancelable(false)
                .show();
        RADIO_WORKER.execute(() -> {
            try {
                T result = action.execute();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    loading.dismiss();
                    success.accept(result);
                });
            } catch (Exception failure) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    loading.dismiss();
                    error(activity, failure.getMessage());
                });
            }
        });
    }

    private static void error(Activity activity, String message) {
        new AlertDialog.Builder(activity)
                .setTitle("Pixel 11 Band Lock")
                .setMessage(message == null ? "The radio operation failed." : message)
                .setPositiveButton("Close", null)
                .show();
    }

    private BandDialog() {}
}
