package cl.chronoremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** TV: al encenderse, queda escuchando a los teléfonos. */
public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { if (U.isTv(c)) TvService.start(c); }
}
