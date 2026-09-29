package android.net;

/** JVM stand-in: reports "no active network" so no connectivity-dependent fallbacks kick in. */
public class ConnectivityManager {
    public ConnectivityManager() { }
    public Network getActiveNetwork() { return null; }
    public NetworkCapabilities getNetworkCapabilities(Network network) { return null; }
    @SuppressWarnings("deprecation")
    public NetworkInfo getActiveNetworkInfo() { return null; }
}
