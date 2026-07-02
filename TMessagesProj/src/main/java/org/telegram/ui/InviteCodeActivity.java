package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.os.PowerManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.supabase.SupabaseConfigDistributor;
import org.telegram.messenger.supabase.SupabaseAuthManager;
import org.telegram.messenger.xray.XrayManager;
import org.telegram.messenger.xray.XrayNode;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.List;

public class InviteCodeActivity extends BaseFragment {
    private EditText codeField;
    private TextView titleTextView;
    private TextView descriptionTextView;
    private RadialProgressView progressView;
    private boolean isChecking = false;

    @Override
    public View createView(Context context) {
        // We do not need an action bar on this screen, but if we do, we can hide it
        actionBar.setBackButtonImage(0);
        actionBar.setTitle("");
        actionBar.setCastShadows(false);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setAddToContainer(false);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(AndroidUtilities.dp(36), AndroidUtilities.dp(72), AndroidUtilities.dp(36), AndroidUtilities.dp(32));

        titleTextView = new TextView(context);
        titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titleTextView.setGravity(Gravity.CENTER);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setText("Код приглашения");
        container.addView(titleTextView, LayoutHelper.createLinear(-1, -2, Gravity.CENTER, 0, 0, 0, 10));

        descriptionTextView = new TextView(context);
        descriptionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        descriptionTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        descriptionTextView.setGravity(Gravity.CENTER);
        descriptionTextView.setText("Введите инвайт-код для активации приложения.");
        container.addView(descriptionTextView, LayoutHelper.createLinear(-1, -2, Gravity.CENTER, 0, 0, 0, 36));

        codeField = new EditText(context);
        codeField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        codeField.setGravity(Gravity.CENTER);
        codeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        codeField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeField.setBackground(Theme.createEditTextDrawable(context, false));
        codeField.setSingleLine(true);
        codeField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                String code = codeField.getText().toString().trim();
                if (!code.isEmpty() && !isChecking) {
                    validateCode(code);
                }
                return true;
            }
            return false;
        });
        container.addView(codeField, LayoutHelper.createLinear(-1, -2, Gravity.CENTER, 0, 0, 0, 24));

        TextView button = new TextView(context);
        button.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        button.setGravity(Gravity.CENTER);
        button.setText("ПРОДОЛЖИТЬ");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(4), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        button.setOnClickListener(v -> {
            String code = codeField.getText().toString().trim();
            if (!code.isEmpty() && !isChecking) {
                validateCode(code);
            }
        });
        container.addView(button, LayoutHelper.createLinear(-1, 48, Gravity.CENTER, 0, 0, 0, 24));

        progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(32));
        progressView.setProgressColor(Theme.getColor(Theme.key_progressCircle));
        progressView.setVisibility(View.INVISIBLE);
        container.addView(progressView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER, 0, 0, 0, 0));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        scrollView.addView(container, lp);
        return scrollView;
    }

    private void validateCode(String code) {
        isChecking = true;
        codeField.setEnabled(false);
        progressView.setVisibility(View.VISIBLE);

        SupabaseAuthManager.getInstance().validateInviteCode(code, result -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (result) {
                    // Success! Code is validated.
                    // Now, fetch xray configs and start Xray
                    SupabaseConfigDistributor.getInstance().fetchConfigs(nodes -> {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (nodes != null && !nodes.isEmpty()) {
                                XrayNode firstNode = nodes.get(0);
                                XrayManager.getInstance().start(firstNode);
                            }
                            
                            isChecking = false;
                            progressView.setVisibility(View.INVISIBLE);
                            
                            // Request ignoring battery optimizations
                            Context act = getParentActivity();
                            if (act != null) {
                                try {
                                    PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
                                    if (pm != null && !pm.isIgnoringBatteryOptimizations(act.getPackageName())) {
                                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                        intent.setData(Uri.parse("package:" + act.getPackageName()));
                                        act.startActivity(intent);
                                    }
                                } catch (Exception e) {
                                    FileLog.e(e);
                                }
                            }
                            
                            // Exit this activity and go to appropriate flow
                            if (parentLayout != null) {
                                if (org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).isClientActivated()) {
                                    parentLayout.presentFragment(new DialogsActivity(null), true, false, true, false);
                                } else {
                                    parentLayout.presentFragment(new IntroActivity(), true, false, true, false);
                                }
                            }
                        });
                    });
                } else {
                    // Fail
                    isChecking = false;
                    codeField.setEnabled(true);
                    codeField.setText("");
                    progressView.setVisibility(View.INVISIBLE);
                    AndroidUtilities.shakeView(codeField);
                    Toast.makeText(getParentActivity(), "Неверный или уже использованный код", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (codeField != null) {
            codeField.requestFocus();
            AndroidUtilities.showKeyboard(codeField);
        }
    }

    @Override
    public boolean hasForceLightStatusBar() {
        return true;
    }
}
