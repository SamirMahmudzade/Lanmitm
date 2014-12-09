package com.oinux.lanmitm.proxy;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import android.util.Log;

import com.oinux.lanmitm.AppContext;
import com.oinux.lanmitm.util.RequestParser;

/**
 * 
 * @author oinux
 *
 */
public class HttpProxy extends Thread {

	public static final String HTTP_POST = "POST";
	public static final String HTTP_GET = "GET";
	private final static int HTTP_SERVER_PORT = 80;
	public static final int HTTP_PROXY_PORT = 8080;
	private static final int BACKLOG = 255;
	public static boolean stop = true;
	private static final Pattern PATTERN = Pattern
			.compile(
					"(\\.css\\??|\\.js\\??|\\.jpg\\??|\\.gif\\??|\\.png\\??|\\.jpeg\\??)",
					Pattern.CASE_INSENSITIVE);

	private ServerSocket mServerSocket;
	private OnRequestListener mOnRequestListener;
	private ExecutorService executor;
	private String inject;

	private static HttpProxy instance;

	public static HttpProxy getInstance() {
		if (instance == null || instance.getState() == State.TERMINATED)
			instance = new HttpProxy();
		return instance;
	}

	private HttpProxy() {
	}

	public String getInject() {
		return inject;
	}

	public void setInject(String inject) {
		this.inject = inject;
	}

	public OnRequestListener getOnRequestListener() {
		return mOnRequestListener;
	}

	public void setOnRequestListener(OnRequestListener onRequestListener) {
		this.mOnRequestListener = onRequestListener;
	}

	@Override
	public synchronized void start() {
		if (this.getState() == State.NEW)
			super.start();
	}

	@Override
	public void run() {
		try {
			mServerSocket = new ServerSocket();
			mServerSocket.setReuseAddress(true);
			mServerSocket.bind(
					new InetSocketAddress(AppContext.getInetAddress(),
							HTTP_PROXY_PORT), BACKLOG);
			executor = Executors.newCachedThreadPool();
			while (!stop) {
				Socket client = mServerSocket.accept();
				executor.execute(new SimpleDealThread(client, mOnRequestListener));
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (mServerSocket != null) {
				try {
					mServerSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (executor != null) {
				executor.shutdownNow();
			}
		}
	}

	class DeepDealThread extends DealThread {

		private String decode = "UTF-8";

		public DeepDealThread(Socket client, OnRequestListener onRequestListener) {
			super(client, onRequestListener);
		}

		private String readResponse(HttpResponse response) {
			HttpEntity entity = response.getEntity();
			InputStream inputStream = null;
			try {
				inputStream = entity.getContent();
				ByteArrayOutputStream content = new ByteArrayOutputStream();

				boolean hasCharset = false;
				Header header = response.getFirstHeader("Content-Type");
				if (header != null) {
					String ct = header.getValue().toLowerCase(
							Locale.getDefault());
					if (ct.contains("charset=utf-8")) {
						decode = "UTF-8";
						hasCharset = true;
					} else if (ct.contains("charset=gbk")) {
						decode = "GBK";
						hasCharset = true;
					} else if (ct.contains("charset=gb2312")) {
						decode = "GB2312";
						hasCharset = true;
					}
				}

				int readBytes = 0;
				byte[] sBuffer = new byte[512];
				while ((readBytes = inputStream.read(sBuffer)) != -1) {
					content.write(sBuffer, 0, readBytes);
				}
				String result = new String(content.toByteArray(), decode);
				if (!hasCharset) {
					String charset = decode;
					Pattern p = Pattern.compile("content=\".*charset=(.*?)\"");
					Matcher m = p.matcher(result);
					if (m.find()) {
						boolean isUnknown = false;
						charset = m.group(1);
						if (charset.equalsIgnoreCase("utf-8")) {
							charset = "UTF-8";
						} else if (charset.equalsIgnoreCase("gbk")) {
							charset = "GBK";
						} else if (charset.equalsIgnoreCase("gb2312")) {
							charset = "GB2312";
						} else {
							isUnknown = true;
						}
						if (!isUnknown) {
							if (!charset.equalsIgnoreCase(decode)) {
								decode = charset;
								result = new String(content.toByteArray(),
										charset);
							}
						}
					}
				}
				return result;
			} catch (IllegalStateException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public void run() {
			byte[] buffer = new byte[1024];
			int read = 0;
			InputStream inputStream = null;

			try {
				inputStream = clientSocket.getInputStream();
				final String clientIp = clientSocket.getInetAddress()
						.getHostAddress();

				if ((read = inputStream.read(buffer, 0, 1024)) >= 0) {
					ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
							buffer, 0, read);
					BufferedReader bReader = new BufferedReader(
							new InputStreamReader(byteArrayInputStream));
					String line = null, serverName = null, path = null, method = null, url = null;
					boolean found = false, foundPath = false, ok = true;
					ArrayList<String> headers = new ArrayList<String>();
					StringBuilder builder = new StringBuilder();

					HttpClient httpClient = new DefaultHttpClient();
					HttpUriRequest request = null;
					List<Header> httpHeaders = new ArrayList<Header>();

					while ((line = bReader.readLine()) != null) {
						headers.add(line);
						builder.append(line + "\r\n");

						if (line.contains("HTTP/1.1"))
							line = line.replace("HTTP/1.1", "HTTP/1.0");
						else if (line.indexOf(':') != -1) {
							String[] split = line.split(":", 2);
							String header = split[0].trim(), value = split[1]
									.trim();
							if (header.equals("Accept-Encoding"))
								value = "identity";
							else if (header.equals("Connection"))
								value = "close";
							else if (header.equals("If-Modified-Since")
									|| header.equals("Cache-Control"))
								header = null;
							if (header != null)
								httpHeaders.add(new BasicHeader(header, value));
						}
						if (!found && line.contains("Host")) {
							serverName = line.substring(5).trim();
							found = true;
						}
						if (!foundPath) {
							path = line;
							foundPath = true;
							Matcher m = PATTERN.matcher(path);
							if (m.find())
								ok = false;
							method = line.substring(0, line.indexOf(' '));
							url = line.substring(line.indexOf(' ') + 1,
									line.lastIndexOf(' '));
						}
					}
					builder.append("\r\n");

					if (serverName != null) {
						writer = new BufferedOutputStream(
								clientSocket.getOutputStream());

						String serverIp = InetAddress.getByName(serverName)
								.getHostAddress();
						if (onRequestListener != null && ok) {
							onRequestListener.onRequest(clientIp, serverName,
									serverIp, path, headers);
						}
						
						if (ok) {
							if (HTTP_GET.equals(method)) {
								request = new HttpGet("http://" + serverName
										+ url);
							} else if (HTTP_POST.equals(method)) {
								request = new HttpPost("http://" + serverName
										+ url);
							}
							if (request != null) {
								request.setHeaders(httpHeaders
										.toArray(new Header[httpHeaders.size()]));
								HttpResponse response = httpClient
										.execute(request);
								if (response != null
										&& response.getStatusLine()
												.getStatusCode() == HttpStatus.SC_OK) {
									String data = null;
									data = readResponse(response);
									writer.write(data.getBytes(decode));
								}
							}
						} else {
							String req = builder.toString();
							serverSocket = new Socket(serverName,
									HTTP_SERVER_PORT);
							serverSocket.setSoTimeout(1000);

							serverReader = serverSocket.getInputStream();
							serverWriter = serverSocket.getOutputStream();

							serverWriter.write(req.getBytes());
							serverWriter.flush();

							byte[] buff = new byte[1024];
							int len = -1;
							while ((len = serverReader.read(buff, 0, 1024)) >= 0) {
								writer.write(buff, 0, len);
								writer.flush();
							}
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					if (inputStream != null) {
						inputStream.close();
					}
					if (writer != null) {
						writer.close();
					}
					if (serverReader != null) {
						serverReader.close();
					}
					if (serverWriter != null) {
						serverWriter.close();
					}
					if (clientSocket != null)
						clientSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

	}

	class SimpleDealThread extends DealThread {

		public SimpleDealThread(Socket client,
				OnRequestListener onRequestListener) {
			super(client, onRequestListener);
		}

		@Override
		public void run() {
			byte[] buffer = new byte[1024];
			int read = 0;
			InputStream inputStream = null;

			try {
				inputStream = clientSocket.getInputStream();
				final String clientIp = clientSocket.getInetAddress()
						.getHostAddress();

				if ((read = inputStream.read(buffer, 0, 1024)) >= 0) {
					ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
							buffer, 0, read);
					BufferedReader bReader = new BufferedReader(
							new InputStreamReader(byteArrayInputStream));
					StringBuilder builder = new StringBuilder();
					String line = null, serverName = null, path = null;
					boolean found = false, foundPath = false, ok = true;
					ArrayList<String> headers = new ArrayList<String>();

					while ((line = bReader.readLine()) != null) {
						headers.add(line);
						if (!found && line.contains("Host")) {
							serverName = line.substring(5).trim();
							found = true;
						}
						if (!foundPath) {
							path = line;
							foundPath = true;
							Matcher m = PATTERN.matcher(path);
							if (m.find())
								ok = false;
						}
						builder.append(line + "\r\n");
					}
					builder.append("\r\n");
					if (serverName != null) {
						String request = builder.toString();
						serverSocket = new Socket(serverName, HTTP_SERVER_PORT);
						serverSocket.setSoTimeout(1000);

						if (onRequestListener != null && ok) {
							onRequestListener.onRequest(clientIp, serverName,
									serverSocket.getInetAddress()
											.getHostAddress(), path, headers);
						}

						writer = new BufferedOutputStream(
								clientSocket.getOutputStream());

						serverReader = serverSocket.getInputStream();
						serverWriter = serverSocket.getOutputStream();

						serverWriter.write(request.getBytes());
						serverWriter.flush();

						byte[] buff = new byte[1024];
						int len = -1;
						while ((len = serverReader.read(buff, 0, 1024)) >= 0) {
							writer.write(buff, 0, len);
							writer.flush();
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					if (inputStream != null) {
						inputStream.close();
					}
					if (writer != null) {
						writer.close();
					}
					if (serverReader != null) {
						serverReader.close();
					}
					if (serverWriter != null) {
						serverWriter.close();
					}
					if (clientSocket != null)
						clientSocket.close();
					if (serverSocket != null)
						serverSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	class DealThread extends Thread {

		protected OnRequestListener onRequestListener;
		protected Socket clientSocket;
		protected Socket serverSocket;
		protected BufferedOutputStream writer = null;
		protected InputStream serverReader = null;
		protected OutputStream serverWriter = null;

		public DealThread(Socket client, OnRequestListener onRequestListener) {
			this.onRequestListener = onRequestListener;
			this.clientSocket = client;
		}
	}

	public interface OnRequestListener {
		public void onRequest(String clientId, String hostname,
				String serverIp, String path, ArrayList<String> headers);
	}
}