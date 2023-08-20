package app.organicmaps.routing;

import static androidx.core.app.NotificationCompat.Builder;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import app.organicmaps.Framework;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.location.LocationHelper;
import app.organicmaps.location.LocationListener;
import app.organicmaps.sound.TtsPlayer;
import app.organicmaps.util.StringUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.util.log.Logger;

public class NavigationService extends Service
{
  private static final String TAG = NavigationService.class.getSimpleName();

  public static final String PACKAGE_NAME = NavigationService.class.getPackage().getName();
  public static final String PACKAGE_NAME_WITH_SERVICE_NAME = PACKAGE_NAME + "." +
      StringUtils.toLowerCase(NavigationService.class.getSimpleName());

  private static final String CHANNEL_ID = "LOCATION_CHANNEL";
  private static final int NOTIFICATION_ID = 12345678;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private String mNavigationText = "";
  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private RemoteViews mRemoteViews;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private NotificationManager mNotificationManager;

  @NonNull
  private final LocationListener mLocationListener = new LocationListener()
  {
    @Override
    public void onLocationUpdated(Location location)
    {
      Logger.d(TAG);
      RoutingInfo routingInfo = Framework.nativeGetRouteFollowingInfo();
      mNotificationManager.notify(NOTIFICATION_ID, getNotification());
      updateNotification(routingInfo);
    }
  };

  @Override
  public void onCreate()
  {
    Logger.i(TAG);

    mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    mRemoteViews = new RemoteViews(getPackageName(), R.layout.navigation_notification);

    // Android O requires a Notification Channel.
    if (Utils.isOreoOrLater())
    {
      CharSequence name = getString(R.string.app_name);
      // Create the channel for the notification
      NotificationChannel mChannel =
          new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
      // Make things less annoying.
      mChannel.enableVibration(false);
      mChannel.enableLights(false);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        mChannel.setAllowBubbles(false);
      mNotificationManager.createNotificationChannel(mChannel);
    }
  }

  @Override
  public void onDestroy()
  {
    Logger.i(TAG);
    super.onDestroy();
    LocationHelper.INSTANCE.removeListener(mLocationListener);
    TtsPlayer.INSTANCE.stop();
  }

  @Override
  public void onLowMemory()
  {
    super.onLowMemory();
    Logger.d(TAG, "onLowMemory()");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    Logger.i(TAG);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      startForegroundS(NOTIFICATION_ID, getNotification());
    else
      startForeground(NOTIFICATION_ID, getNotification());

    LocationHelper.INSTANCE.addListener(mLocationListener);

    return START_NOT_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    Logger.i(TAG);
    return null;
  }

  @RequiresApi(api = Build.VERSION_CODES.S)
  private void startForegroundS(int id, Notification notification)
  {
    try
    {
      startForeground(id, notification);
      Logger.w(TAG, "ForegroundService is allowed");
    }
    catch (ForegroundServiceStartNotAllowedException e)
    {
      Logger.e(TAG, "Oops! ForegroundService is not allowed", e);
    }
  }

  @NonNull
  private Notification getNotification()
  {
    final int FLAG_IMMUTABLE = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;

    final Intent stopIntent = new Intent(this, MwmActivity.class);
    stopIntent.putExtra(MwmActivity.EXTRA_STOP_NAVIGATION, true);
    stopIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    final PendingIntent stopPendingIntent = PendingIntent.getActivity(this, 0, stopIntent,
        PendingIntent.FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);

    final Intent contentIntent = new Intent(this, MwmActivity.class);
    final PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent, FLAG_IMMUTABLE);

    Builder builder = new Builder(this, CHANNEL_ID)
        .addAction(R.drawable.ic_cancel, getString(R.string.button_exit), stopPendingIntent)
        .setContentIntent(contentPendingIntent)
        .setOngoing(true)
        .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(mRemoteViews)
        .setCustomHeadsUpContentView(mRemoteViews)
        .setPriority(Notification.PRIORITY_HIGH)
        .setSmallIcon(R.drawable.ic_notification)
        .setShowWhen(true);

    if (Utils.isOreoOrLater())
      builder.setChannelId(CHANNEL_ID);

    return builder.build();
  }

  private void updateNotification(@Nullable RoutingInfo routingInfo)
  {
    final RoutingController routingController = RoutingController.get();
    // Ignore any pending notifications when service is stopping.
    if (!routingController.isNavigating())
      return;

    final String[] turnNotifications = Framework.nativeGenerateNotifications();
    if (turnNotifications != null)
    {
      mNavigationText = StringUtils.fixCaseInString(turnNotifications[0]);
      TtsPlayer.INSTANCE.playTurnNotifications(getApplicationContext(), turnNotifications);
      mRemoteViews.setTextViewText(R.id.navigation_text, mNavigationText);
      mRemoteViews.setViewVisibility(R.id.navigation_text, TextUtils.isEmpty(mNavigationText) ? View.GONE : View.VISIBLE);
    }

    final StringBuilder secondaryTextBuilder = new StringBuilder();
    final String routingArriveString = getString(R.string.routing_arrive);
    secondaryTextBuilder.append(String.format(routingArriveString, routingController.getEndPoint().getName()));
    if (routingInfo != null)
    {
      secondaryTextBuilder
          .append(": ")
          .append(Utils.calculateFinishTime(routingInfo.totalTimeInSeconds));
      mRemoteViews.setImageViewResource(R.id.navigation_icon, routingInfo.carDirection.getTurnRes());
      mRemoteViews.setTextViewText(R.id.navigation_distance_text, routingInfo.distToTurn.toString(getApplicationContext()));
    }
    final String secondaryText = secondaryTextBuilder.toString();
    mRemoteViews.setTextViewText(R.id.navigation_secondary_text, secondaryText);
    mRemoteViews.setViewVisibility(R.id.navigation_secondary_text, TextUtils.isEmpty(secondaryText) ? View.GONE : View.VISIBLE);
  }
}
