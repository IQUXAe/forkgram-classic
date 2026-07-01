package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.xray.XrayManager;
import org.telegram.tgnet.ConnectionsManager;

public class XrayStatusView extends View implements NotificationCenter.NotificationCenterDelegate {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int currentColor = 0xffff0000; // Red by default

    public XrayStatusView(Context context) {
        super(context);
        updateStatus();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.xrayStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.xrayNodeRotated);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        updateStatus();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.xrayStateChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.xrayNodeRotated);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.xrayStateChanged || 
            id == NotificationCenter.xrayNodeRotated || 
            id == NotificationCenter.didUpdateConnectionState) {
            AndroidUtilities.runOnUIThread(this::updateStatus);
        }
    }

    private void updateStatus() {
        XrayManager.State xrayState = XrayManager.getInstance().getState();
        int connState = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();

        if (xrayState == XrayManager.State.RUNNING) {
            if (connState == ConnectionsManager.ConnectionStateConnected || 
                connState == ConnectionsManager.ConnectionStateUpdating) {
                currentColor = 0xff4caf50; // Green 🟢
            } else {
                currentColor = 0xffffeb3b; // Yellow 🟡 (Proxy running but TG connecting)
            }
        } else if (xrayState == XrayManager.State.STARTING) {
            currentColor = 0xffffeb3b; // Yellow 🟡
        } else {
            currentColor = 0xfff44336; // Red 🔴
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(AndroidUtilities.dp(16), AndroidUtilities.dp(16));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(currentColor);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = AndroidUtilities.dp(4);
        canvas.drawCircle(cx, cy, radius, paint);
    }
}
