package app.autospace.host

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import org.xmlpull.v1.XmlPullParser

/**
 * An app's Android Auto descriptor: the `com.google.android.gms.car.application` meta-data,
 * pointing at an xml file whose `<uses name="..."/>` lines say what the app does in a car -
 * `media`, `notification` (messaging), `template` and so on.
 */
object CarDescriptor {

    const val META = "com.google.android.gms.car.application"

    /** The meta-data holding the descriptor, from the service first and then the app. */
    fun meta(manager: PackageManager, pkg: String, service: ServiceInfo? = null): Bundle? =
        service?.metaData?.takeIf { it.containsKey(META) }
            ?: runCatching { manager.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData }
                .getOrNull()?.takeIf { it.containsKey(META) }

    /**
     * The `uses` names in [pkg]'s descriptor. Null when it has no descriptor, or one that can't
     * be read; empty when it lists nothing.
     */
    fun uses(manager: PackageManager, pkg: String, service: ServiceInfo? = null): Set<String>? {
        val resId = meta(manager, pkg, service)?.getInt(META) ?: return null
        if (resId == 0) return null
        return runCatching {
            manager.getResourcesForApplication(pkg).getXml(resId).use { xml ->
                buildSet {
                    while (xml.next() != XmlPullParser.END_DOCUMENT) {
                        if (xml.eventType == XmlPullParser.START_TAG && xml.name == "uses") {
                            xml.getAttributeValue(null, "name")?.let(::add)
                        }
                    }
                }
            }
        }.getOrNull()
    }
}
