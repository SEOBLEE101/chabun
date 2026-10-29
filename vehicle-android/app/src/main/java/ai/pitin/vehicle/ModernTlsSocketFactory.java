package ai.pitin.vehicle;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Retains platform trust/hostname validation and permits TLS 1.2/1.3 only. */
final class ModernTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    ModernTlsSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
    private Socket secure(Socket socket) throws IOException {
        SSLSocket ssl = (SSLSocket) socket;
        List<String> supported = Arrays.asList(ssl.getSupportedProtocols());
        List<String> enabled = new ArrayList<>();
        if (supported.contains("TLSv1.3")) enabled.add("TLSv1.3");
        if (supported.contains("TLSv1.2")) enabled.add("TLSv1.2");
        if (enabled.isEmpty()) { socket.close(); throw new IOException("Secure TLS is unavailable"); }
        ssl.setEnabledProtocols(enabled.toArray(new String[0]));
        return ssl;
    }
    public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException {
        return secure(delegate.createSocket(s, h, p, close));
    }
    public Socket createSocket(String h, int p) throws IOException { return secure(delegate.createSocket(h, p)); }
    public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException {
        return secure(delegate.createSocket(h, p, l, lp));
    }
    public Socket createSocket(InetAddress h, int p) throws IOException { return secure(delegate.createSocket(h, p)); }
    public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException {
        return secure(delegate.createSocket(h, p, l, lp));
    }
}
