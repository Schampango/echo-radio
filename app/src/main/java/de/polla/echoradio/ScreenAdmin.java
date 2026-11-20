package de.polla.echoradio;

import android.app.admin.DeviceAdminReceiver;

/**
 * Geraeteadministrator nur mit "Bildschirm sperren", damit die Nachtschaltung das
 * Display ausschalten darf. Einschalten: adb shell dpm set-active-admin de.polla.echoradio/.ScreenAdmin
 */
public class ScreenAdmin extends DeviceAdminReceiver {
}
