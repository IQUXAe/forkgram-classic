package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.supabase.SupabaseAuthManager;
import org.telegram.messenger.supabase.SupabaseConfigDistributor;
import org.telegram.messenger.xray.XrayManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class BypassStatusActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private TextDetailSettingsCell deviceUidCell;
    private TextDetailSettingsCell authStatusCell;
    private TextDetailSettingsCell xrayStateCell;
    private TextDetailSettingsCell xrayPortCell;
    private TextDetailSettingsCell configsCountCell;
    private TextDetailSettingsCell vpnStatusCell;
    
    private TextView testButton;
    private TextView logoutButton;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.xrayStateChanged);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.xrayStateChanged);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Отладочная информация");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout sectionLayout = new LinearLayout(context);
        sectionLayout.setOrientation(LinearLayout.VERTICAL);
        sectionLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        linearLayout.addView(sectionLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 12));

        deviceUidCell = new TextDetailSettingsCell(context);
        sectionLayout.addView(deviceUidCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        authStatusCell = new TextDetailSettingsCell(context);
        sectionLayout.addView(authStatusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        xrayStateCell = new TextDetailSettingsCell(context);
        sectionLayout.addView(xrayStateCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        xrayPortCell = new TextDetailSettingsCell(context);
        sectionLayout.addView(xrayPortCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        configsCountCell = new TextDetailSettingsCell(context);
        sectionLayout.addView(configsCountCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        vpnStatusCell = new TextDetailSettingsCell(context);
        sectionLayout.addView(vpnStatusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Add Test Connection button
        testButton = new TextView(context);
        testButton.setGravity(Gravity.CENTER);
        testButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        testButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        testButton.setText("Проверить соединение");
        testButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        testButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        
        linearLayout.addView(testButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));

        testButton.setOnClickListener(v -> {
            testConnection(context);
        });

        // Add Logout button
        logoutButton = new TextView(context);
        logoutButton.setGravity(Gravity.CENTER);
        logoutButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        logoutButton.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        logoutButton.setText("Сбросить авторизацию прокси");
        logoutButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), Color.TRANSPARENT, Theme.getColor(Theme.key_listSelector)));
        logoutButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        
        linearLayout.addView(logoutButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 16));

        logoutButton.setOnClickListener(v -> {
            SupabaseAuthManager.getInstance().logout();
        });

        updateValues();

        fragmentView = scrollView;
        return fragmentView;
    }

    private void updateValues() {
        if (getParentActivity() == null) return;

        String deviceUid = SupabaseAuthManager.getInstance().getDeviceUid();
        deviceUidCell.setTextAndValue("Идентификатор устройства (Device UID)", deviceUid != null ? deviceUid : "Не определен", true);

        boolean authorized = SupabaseAuthManager.getInstance().isLocallyAuthorized();
        authStatusCell.setTextAndValue("Статус авторизации", authorized ? "Авторизован" : "Не авторизован (требуется код)", true);

        XrayManager.State state = XrayManager.getInstance().getState();
        String stateStr = "Остановлен";
        if (state == XrayManager.State.RUNNING) {
            stateStr = "Активен (Обход работает)";
        } else if (state == XrayManager.State.STARTING) {
            stateStr = "Запуск...";
        } else if (state == XrayManager.State.ERROR) {
            stateStr = "Ошибка запуска";
        }
        xrayStateCell.setTextAndValue("Состояние Xray", stateStr, true);

        int port = XrayManager.getInstance().getLocalPort();
        xrayPortCell.setTextAndValue("Локальный порт прокси", port > 0 ? String.valueOf(port) : "Не назначен", true);

        int configsCount = SupabaseConfigDistributor.getInstance().getCachedConfigs().size();
        configsCountCell.setTextAndValue("Загружено конфигураций обхода", String.valueOf(configsCount), true);

        boolean vpnActive = XrayManager.isVpnActive(getParentActivity());
        vpnStatusCell.setTextAndValue("Системный VPN", vpnActive ? "Обнаружен (Xray на паузе, обход идет через ваш VPN)" : "Не обнаружен (работает локальный обход)", false);
    }

    private void testConnection(Context context) {
        testButton.setEnabled(false);
        testButton.setText("Проверка...");
        
        SupabaseConfigDistributor.getInstance().fetchConfigs(nodes -> AndroidUtilities.runOnUIThread(() -> {
            testButton.setEnabled(true);
            testButton.setText("Проверить соединение");
            updateValues();
            
            if (nodes != null && !nodes.isEmpty()) {
                Toast.makeText(context, "Соединение успешно! Загружено конфигов: " + nodes.size(), Toast.LENGTH_SHORT).show();
                if (SupabaseAuthManager.getInstance().isLocallyAuthorized()) {
                    XrayManager.getInstance().restart(nodes.get(0));
                }
            } else {
                Toast.makeText(context, "Ошибка соединения или нет доступных конфигураций", Toast.LENGTH_LONG).show();
            }
        }));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.xrayStateChanged) {
            AndroidUtilities.runOnUIThread(this::updateValues);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        return descriptions;
    }
}
