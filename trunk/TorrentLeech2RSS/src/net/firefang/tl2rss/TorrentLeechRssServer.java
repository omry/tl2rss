package net.firefang.tl2rss;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.jasper.servlet.JspServlet;
import org.apache.log4j.xml.DOMConfigurator;
import org.htmlparser.Node;
import org.htmlparser.NodeFilter;
import org.htmlparser.Parser;
import org.htmlparser.filters.AndFilter;
import org.htmlparser.filters.HasAttributeFilter;
import org.htmlparser.filters.HasChildFilter;
import org.htmlparser.filters.HasSiblingFilter;
import org.htmlparser.filters.NodeClassFilter;
import org.htmlparser.filters.NotFilter;
import org.htmlparser.filters.TagNameFilter;
import org.htmlparser.lexer.Lexer;
import org.htmlparser.lexer.Page;
import org.htmlparser.tags.LinkTag;
import org.htmlparser.tags.TableColumn;
import org.htmlparser.util.NodeList;
import org.htmlparser.util.ParserException;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.DefaultHandler;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.log.Log;
import org.mortbay.resource.Resource;

import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndEntryImpl;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.feed.synd.SyndFeedImpl;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedOutput;
import com.sun.syndication.io.impl.DateParser;

/**
 * @author omry
 */
public class TorrentLeechRssServer
{
	public static TorrentLeechRssServer instance;
	private Map<String, Torrent> m_torrents = new HashMap<String, Torrent>();
	
	private String m_host;
	private int m_port;
	
	private int m_updateInterval;

	private String m_updateCategories;

	protected long m_torrentTimeoutDays;
	
	Map<String,String> m_cookies = new HashMap<String, String>();
	
	public TorrentLeechRssServer(Properties props) throws Exception
	{
		instance = this;
		loadCookies();
		
		m_updateCategories = props.getProperty("update_categories", "7");
		m_host = props.getProperty("host", InetAddress.getLocalHost().getHostName());
		m_port = Integer.parseInt(props.getProperty("port", "8080"));
		m_torrentTimeoutDays = Integer.parseInt(props.getProperty("torrent_timeout_days", "7"));
		Log.info("Running from " + m_host + ":" + m_port);
		
		m_updateInterval = Integer.parseInt(props.getProperty("update_interval", "25"));
		
		Server server = new Server(m_port);
		server.setHandler(new Handler());
		
        server.setHandler(new Handler());
        Context root = new Context(server, "/", Context.SESSIONS);
        root.setBaseResource(Resource.newResource("file://" + new File("").getAbsolutePath() + "/jsp/", false));
//        root.addServlet(new ServletHolder(new DefaultServlet()), "/");
        root.addServlet(new ServletHolder(new DefaultServlet()), "/res/*");
        root.addServlet(new ServletHolder(new JspServlet()), "*.jsp");
        root.setWelcomeFiles(new String[]{"index.jsp"});
        server.addHandler(root);
		
		try
		{
			server.start();
		} catch (Exception e)
		{
			System.exit(1);
		}
		
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			public void run()
			{
				saveCookies();
			}
		});
		
		startUpdateThread();
		startCleanupThread();
	}
	
	private void loadCookies()
	{
		if (!new File("cookies.txt").exists()) return;
		
		Properties props = new Properties();
		FileInputStream fin = null;
		try
		{
			fin = new FileInputStream("cookies.txt");
			props.load(fin);
			Enumeration<Object> keys = props.keys();
			while(keys.hasMoreElements())
			{
				String key =(String)keys.nextElement();
				String value = props.getProperty(key);
				m_cookies.put(key, value);
				Log.info("Loaded cookie " + key + "=" + value);
			}
		} catch (IOException e)
		{
			Log.warn(e);
		}
		finally
		{
			try
			{
				fin.close();
			} catch (IOException e)
			{
			}
		}
	}

	protected void saveCookies()
	{
		try
		{
			FileOutputStream fos = new FileOutputStream("cookies.txt");
			try
			{
				for(String k : m_cookies.keySet())
				{
					String v = m_cookies.get(k);
					fos.write((k + "=" + v + "\n").getBytes());
				}
			}
			finally
			{
				fos.close();
			}
		} 
		catch (IOException e)
		{
			Log.warn(e);
		}
	}

	private void startCleanupThread()
	{
		Thread t = new Thread("Cleanup thread")
		{
			
			public void run()
			{
				while(true)
				{
					long now = System.currentTimeMillis();
					Iterator<String> keys = m_torrents.keySet().iterator();
					while(keys.hasNext())
					{
						String id = keys.next();
						Torrent t = m_torrents.get(id);
						if (now - t.creationTime > (m_torrentTimeoutDays * 24 * 60 * 60 * 1000));
						{
							Log.info("Torrent expired, removing " + t.name);
							keys.remove();
						}
					}
					
					try
					{
						Thread.sleep(60 * 60 * 10000);
					} catch (InterruptedException e)
					{
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	private void startUpdateThread() throws IOException
	{
		m_updateThread = new Thread("Torrents update thread")
		{
			public void run()
			{
				try
				{
					while(true)
					{
						try
						{
							while(!isAuthenticated())
							{
								synchronized (TorrentLeechRssServer.this)
								{
									try
									{
										Log.info("Waiting for authentication");
										TorrentLeechRssServer.this.wait();
									} catch (InterruptedException e)
									{
									}
								}
							}
							
							updateTorrents();
							if (!isAuthenticated()) continue;
						} catch (IOException e1)
						{
							Log.info("IOException when updating torrents",e1);
						}
						
						synchronized (TorrentLeechRssServer.this)
						{
							try
							{
								TorrentLeechRssServer.this.wait(m_updateInterval * 60 * 1000);
							} catch (InterruptedException e)
							{
							}
						}
					}
				}
				finally
				{
					Log.info("Update thread exited");
				}
			}
		};
		m_updateThread.setDaemon(true);
		m_updateThread.start();
	}
	
	long m_lastUpdate;
	private Thread m_updateThread;
	protected void updateTorrents() throws IOException
	{
		Log.info("Updating torrents");
		if (System.currentTimeMillis() - m_lastUpdate < (m_updateInterval-1) * 60 * 1000) return;

		StringTokenizer tok = new StringTokenizer(m_updateCategories, ", ");
		while(tok.hasMoreElements())
		{
			int cat = Integer.parseInt(tok.nextToken());
			try
			{
				updateCategory(cat, m_lastUpdate == 0);
			} catch (ParserException e)
			{
				Log.warn("Error parsing html of category " + cat,e);
			} 
		}
		m_lastUpdate = System.currentTimeMillis();
	}

	@SuppressWarnings("unchecked")
	public String getRSS(Set<Integer> cats) throws FeedException
	{
		String feedType = "rss_2.0";
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType(feedType);

		feed.setTitle("TorrentLeech RSS Feed");
		String baseUrl = "http://" + m_host + (m_port != 80? ":"+m_port :"" );
		feed.setLink(baseUrl);
		feed.setDescription("This feed have been craeted by TorrentLeech2RSS, see http://tl2rss.firefang.net for more info");
		List entries = new ArrayList();
		feed.setEntries(entries);
		
		Vector v = new Vector();
		for(Torrent t : m_torrents.values())
		{
			if (cats == null || cats.contains(t.cat))
				v.add(t);
		}
		
		Collections.sort(v, new Comparator()
		{
			public int compare(Object t1, Object t2)
			{
				//2007-12-05 12:50:43
				SimpleDateFormat parser = new SimpleDateFormat("yyyy-mm-dd HH:mm:ss");
				Torrent a = (Torrent) t1;
				Torrent b = (Torrent) t2;
				try
				{
					Date d1 = parser.parse(a.date);
					Date d2 = parser.parse(b.date);
					return d2.compareTo(d1);
				} 
				catch (ParseException e)
				{
					return 0;
				}
			}
		});
		
		for (int i = 0; i < v.size(); i++)
		{
			Torrent t = (Torrent) v.elementAt(i);
			
			SyndEntry entry = new SyndEntryImpl();
			entry.setTitle(t.name);
			entry.setLink(baseUrl + "/download?id=" + t.id);
			entry.setPublishedDate(DateParser.parseDate(t.date));
			entries.add(entry);
		}

		
		SyndFeedOutput output = new SyndFeedOutput();
		return output.outputString(feed);
	}


	private Torrent getTorrent(String id, int cat)
	{
		if (!m_torrents.containsKey(id))
		{
			m_torrents.put(id, new Torrent(id, cat));
		}
		
		return m_torrents.get(id);
	}

	private void updateCategory(int cat, boolean firstRun) throws ParserException, IOException 
	{
		List<String> urls = new ArrayList<String>();
		urls.add("http://www.torrentleech.org/browse.php?cat="+cat);
		if (firstRun) // grab a few more additional pages
		{
			urls.add("http://www.torrentleech.org/browse.php?page=1&cat="+cat);
			urls.add("http://www.torrentleech.org/browse.php?page=2&cat="+cat);
			urls.add("http://www.torrentleech.org/browse.php?page=3&cat="+cat);
		}

		for(String u : urls)
		{
			URL url = new URL(u);
			Log.info("Updating torrents : " + url);
			URLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
			conn.setRequestProperty("Cookie", getCookiesString());
			InputStream in = conn.getInputStream();
    	    List<String> cookies = (List<String>) conn.getHeaderFields().get("Set-Cookie");
	        boolean authenticated = handleCookies(cookies);
			if (!authenticated) 
			{
				Log.warn("No longer authenticated, aborting torrents update");
				return;
			}

			try
			{
				Log.info(m_cookies.toString());
				processTorrentsStream(cat, in);
			}
			finally
			{
				in.close();
			}
		}
	}
	
	private void processTorrentsStream(int cat, InputStream in) throws UnsupportedEncodingException, ParserException
	{
		Parser parser = new Parser(new Lexer(new Page(in, "UTF-8")), Parser.DEVNULL);
		NodeList res = parser.extractAllNodesThatMatch(getDownloadsFilter());
		Pattern detailsLink = Pattern.compile("details.php\\?id=(.*)&amp.*");
		Pattern downloadLink = Pattern.compile("download.php/(.*)/.*");
		for (int i = 0; i < res.size(); i++)
		{
			Torrent torrent = null;
			Node node = res.elementAt(i);
			NodeList children = node.getChildren();
			for (int j = 0; j < children.size(); j++)
			{
				Node c = children.elementAt(j);
				if (c instanceof TableColumn)
				{
					TableColumn td = (TableColumn) c;
					if (td.getChildCount() >= 3)
					{
						if (td.childAt(0) instanceof LinkTag)
						{
							LinkTag href = (LinkTag) td.childAt(0);
							String link = href.extractLink();
							Matcher matcher = detailsLink.matcher(link);
							if (matcher.matches())
							{
								String id = matcher.group(1);
								torrent = getTorrent(id, cat);
								
								if (href.getChildCount() > 2)
								{
									String name = href.childAt(1).getText();
									torrent.name = name;
								}
							}
							String date = td.getChild(3).getText(); 
							torrent.date = date;
						}
						else
						if (td.childAt(1) instanceof LinkTag)
						{
							LinkTag href = (LinkTag) td.childAt(1);
							String link = href.extractLink();
							Matcher matcher = downloadLink.matcher(link);
							if (matcher.matches())
							{
								String id = matcher.group(1);
								if (torrent == null)
								{
									torrent = getTorrent(id, cat);
								}
								torrent.downloadLink = "http://www.torrentleech.org/" + link;
							}
						}
					}
				}
			}
		}		
	}

	
	private InputStream doGet(String u) throws IOException
	{
		URL url = new URL(u);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(30000);
		conn.setReadTimeout(30000);
		conn.setRequestProperty("Cookie", getCookiesString());
		return new BufferedInputStream(conn.getInputStream(), 4096);
	}

	private NodeFilter getDownloadsFilter()
	{
        NodeClassFilter filter0 = new NodeClassFilter ();
        try { filter0.setMatchClass (Class.forName ("org.htmlparser.tags.TableRow")); } catch (ClassNotFoundException cnfe) { cnfe.printStackTrace (); }
        NodeClassFilter filter1 = new NodeClassFilter ();
        try { filter1.setMatchClass (Class.forName ("org.htmlparser.tags.TableColumn")); } catch (ClassNotFoundException cnfe) { cnfe.printStackTrace (); }
        TagNameFilter filter2 = new TagNameFilter ();
        filter2.setName ("CENTER");
        NodeClassFilter filter3 = new NodeClassFilter ();
        try { filter3.setMatchClass (Class.forName ("org.htmlparser.tags.LinkTag")); } catch (ClassNotFoundException cnfe) { cnfe.printStackTrace (); }
        HasSiblingFilter filter4 = new HasSiblingFilter ();
        filter4.setSiblingFilter (filter3);
        NodeFilter[] array0 = new NodeFilter[2];
        array0[0] = filter2;
        array0[1] = filter4;
        AndFilter filter5 = new AndFilter ();
        filter5.setPredicates (array0);
        HasChildFilter filter6 = new HasChildFilter ();
        filter6.setRecursive (false);
        filter6.setChildFilter (filter5);
        NodeFilter[] array1 = new NodeFilter[2];
        array1[0] = filter1;
        array1[1] = filter6;
        AndFilter filter7 = new AndFilter ();
        filter7.setPredicates (array1);
        HasChildFilter filter8 = new HasChildFilter ();
        filter8.setRecursive (true);
        filter8.setChildFilter (filter7);
        NodeClassFilter filter9 = new NodeClassFilter ();
        try { filter9.setMatchClass (Class.forName ("org.htmlparser.tags.TableColumn")); } catch (ClassNotFoundException cnfe) { cnfe.printStackTrace (); }
        HasAttributeFilter filter10 = new HasAttributeFilter ();
        filter10.setAttributeName ("class");
        filter10.setAttributeValue ("xexe");
        NodeFilter[] array2 = new NodeFilter[2];
        array2[0] = filter9;
        array2[1] = filter10;
        AndFilter filter11 = new AndFilter ();
        filter11.setPredicates (array2);
        HasChildFilter filter12 = new HasChildFilter ();
        filter12.setRecursive (false);
        filter12.setChildFilter (filter11);
        NotFilter filter13 = new NotFilter ();
        filter13.setPredicate (filter12);
        NodeFilter[] array3 = new NodeFilter[3];
        array3[0] = filter0;
        array3[1] = filter8;
        array3[2] = filter13;
        AndFilter filter14 = new AndFilter ();
        filter14.setPredicates (array3);
        return filter14;
	}
	
	private final class Handler extends DefaultHandler
	{
		public void handle(String target, HttpServletRequest request,
				HttpServletResponse response, int dispatch) throws IOException,
				ServletException
		{
		    if (target.startsWith("/proxy/"))
		    {
		    	proxy(request, response, target.substring("/proxy/".length()));
		    	((Request)request).setHandled(true);
		    }
		    else
			if (target.equals("/download"))
			{
		        String id = request.getParameter("id");
		        if (id == null) throw new ServletException("Missing id parameter");
		        Torrent t = m_torrents.get(id);
		        InputStream in = doGet(t.downloadLink);
		        response.setContentType("application/x-bittorrent");
		        OutputStream out = response.getOutputStream();
		        int c;
		        while((c = in.read()) != -1) out.write(c);
		        ((Request)request).setHandled(true);
			}
			else
			if (target.equals("/rss"))
			{
				if (isAuthenticated())
				{
					response.setContentType("text/xml");
					try
					{
						Set<Integer> cats = null;
						String cc = request.getParameter("cat");
						if (cc != null)
						{
							cats = new HashSet<Integer>(); 
							StringTokenizer t = new StringTokenizer(cc, ",");
							while(t.hasMoreElements())
								cats.add(Integer.parseInt(t.nextToken()));
						}
						response.getWriter().write(getRSS(cats));
					}
					catch (FeedException e)
					{
						throw new ServletException(e);
					}
				}
				else
				{
		    		response.setContentType("text/html");
		    		response.getWriter().write("Not authenticated, you need to <a href='/proxy/login.php'>Login</a>");
				}
				((Request)request).setHandled(true);
			}
			else
			if (target.equals("/logout"))
			{
				m_cookies.clear();
				saveCookies();
				response.sendRedirect("index.jsp");
				((Request)request).setHandled(true);
			}
			if (target.equals("/"))
			{
				response.sendRedirect("index.jsp");
			}
		}
	}

	private static class Torrent
	{
		String id; 
		String name;
		String date;
		String downloadLink;
		long creationTime;
		int cat;
		
		public Torrent(String id, int c)
		{
			cat = c;
			creationTime = System.currentTimeMillis();
			this.id = id;
		}
		
		public String toString()
		{
			return id + " " + name + ", " + date + ", " + downloadLink;
		}
	}
	

	private boolean handleCookies(List<String> cookies) throws IOException
	{
		boolean wasAuthenticated = isAuthenticated();
		for(int i=0;cookies != null && i<cookies.size();i++)
		{
			String s = cookies.get(i);
			StringTokenizer t = new StringTokenizer(s, "="); // key=value;exp;location
			String key = t.nextToken();
			StringTokenizer toks = new StringTokenizer(t.nextToken(),";");
			String value = toks.nextToken();
			if (value.equals("deleted"))
			{
				m_cookies.remove(key);
			}
			else
			{
				m_cookies.put(key, value);
			}
		}

		saveCookies();
		
		if (!wasAuthenticated && isAuthenticated())
		{
			Log.info("Authenticated, updateing torrents");
			m_lastUpdate = 0;
			updateTorrents();
			synchronized (this)
			{
				notifyAll();
			}
		}
		return isAuthenticated();
	}
	
	public boolean isAuthenticated()
	{
		if (m_cookies.get("uid")== null) return false;
		if (m_cookies.get("pass")== null) return false;
		return true;
	}
	
	private String getCookiesString()
	{
		// touch timestamps
		m_cookies.put("last_access", "" + (System.currentTimeMillis() / 1000));
		m_cookies.put("last_browse", "" + (System.currentTimeMillis() / 1000));

		StringBuffer b = new StringBuffer();
		Iterator<String> keys = m_cookies.keySet().iterator();
		while(keys.hasNext())
		{
			String key = keys.next();
			String value = (String) m_cookies.get(key);
			b.append(key + "=" + value);
			if (keys.hasNext())
			{
				b.append(";");
			}
		}
		return b.toString();
	}
	
	
	public static void main(String[] args) throws Exception
	{
		Properties props = new Properties();
		File file = new File("conf.properties");
		if (!file.exists())
		{
			File f = new File("sample-conf.properties");
			byte fdata[] = new byte[(int)f.length()];
			DataInputStream in = new DataInputStream(new FileInputStream(f));
			in.readFully(fdata);
			props.load(new ByteArrayInputStream(fdata));
			FileOutputStream fout = new FileOutputStream("conf.properties");
			try
			{
				fout.write(fdata);
			}
			finally
			{
				fout.close();
			}
			Log.info("Created a new conf.propeties file");
		}
		props.load(new FileInputStream(file));
		DOMConfigurator.configure("log4j.xml");
		new TorrentLeechRssServer(props);
	}
	
	@SuppressWarnings("unchecked")
	void proxy(HttpServletRequest request, HttpServletResponse response, String target) throws IOException
	{
 		final boolean debug = true;
		HttpURLConnection.setFollowRedirects(false);
		String torrentleech = "www.torrentleech.org";
		String host = torrentleech;
		String referrer = null;
		URL url;
		if (target.startsWith("external:"))
		{
			url = new URL(target.substring("external:".length()) +getParameters(request) );
			target = url.toString();
			host = url.getHost();
			referrer = "http://"+ torrentleech;
		}
		else
		{
			url = new URL("http://"+host+"/" + target);
		}
		
		if (debug) Log.info("proxying to " + url);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		Enumeration headers = request.getHeaderNames();
		while(headers.hasMoreElements())
		{
			String k = (String) headers.nextElement();
			String v = request.getHeader(k);
			if (k.equals("Accept-Encoding"))
			{
				if (debug) Log.info("Skipping "  + k + " = " + v);
				continue; // skip
			}
			
			if (k.equals("Host"))
			{
				v = host;
			}
			if (k.equals("Referer"))
			{
				if (referrer == null)
				{
					int i = v.indexOf("/torrentleech");
					int i2 = v.indexOf("/proxy");
					if (i != -1)
					{
						v = "http://" + host + v.substring(i + "/torrentleech".length());
					}
					else
					if (i2 != -1)
					{
						v = "http://" + host + "/" + v.substring(i2 + "/proxy/".length());
					}
				}
				else
				{
					v = referrer;
				}
			}
			
			if (debug) Log.info("request header: " + k + "=" + v);
			conn.addRequestProperty(k, v);
		}
		
		if (request.getMethod() == "POST")
		{
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			OutputStream out = conn.getOutputStream();
	        BufferedInputStream bin = new BufferedInputStream(request.getInputStream(), 4096);
	        byte buff[] = new byte[4096];
	        int len;
	        StringBuffer sb = new StringBuffer();
	        while((len = bin.read(buff)) != -1) 
	        {
	        	out.write(buff, 0, len);
	        	sb.append(new String(buff, 0, len));
	        }
//	        if (debug) Log.info("Sent " + sb);
		}
		
		int code = conn.getResponseCode();
		InputStream in = conn.getInputStream();
		
		List<String> cookies = (List<String>) conn.getHeaderFields().get("Set-Cookie");
		handleCookies(cookies);
		
		if (!isAuthenticated())
		{
			Map<String, String> responsProps = new HashMap<String, String>();
			response.setStatus(code);
			int i = 1;
			while(true)
			{
				String k = conn.getHeaderFieldKey(i);
				if (k == null) break;
				String v = conn.getHeaderField(i);
				i++;
				responsProps.put(k, v);
				if (debug) Log.info("response header: " + k + "=" + v);
			}
			
			BufferedInputStream bin = new BufferedInputStream(in, 4096);
			int contentLength = conn.getContentLength();
			byte buffer[];
			if (contentLength != -1)
			{
				buffer = new byte[contentLength];
				new DataInputStream(bin).readFully(buffer);
			}
			else
			{
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				int c;
				while((c = bin.read()) != -1) 
				{
					bout.write((char)c);
				}
				buffer = bout.toByteArray();
			}
			
			String ct = responsProps.get("Content-Type");
			boolean goodCt = (ct != null && (ct.startsWith("text/html") || ct.startsWith("text/javascript")));
			byte data[] = null;
			if (goodCt)
			{
				data = filterResponse(target, request, responsProps, new String(buffer)).getBytes();
			}
			else
			{
				data = buffer;
			}
			
			for(String k : responsProps.keySet())
			{
				String v = responsProps.get(k);
				if (k.equalsIgnoreCase("Location")) // http redirect, proxify
				{
					if (v.startsWith("http://www.torrentleech.org/"))
					{
						String path = v.substring("http://www.torrentleech.org/".length());
						v = "http://"+ m_host + ":" + m_port + "/proxy/"+path;
					}
					else
						v = "http://"+ m_host + ":" + m_port + "/proxy/external:" + v;
				}
				response.setHeader(k, v);
			}
			response.setContentLength(data.length);
			OutputStream out = response.getOutputStream();
			out.write(data);
//			if (debug) Log.info("Received " + sb);
		}
		else
		{
			Log.info("Authenticated");
			response.sendRedirect("/");
		}
	}

	@SuppressWarnings("unchecked")
	private String getParameters(HttpServletRequest request)
	{
		String res = "";
		Enumeration names = request.getParameterNames();
		while(names.hasMoreElements())
		{
			String name = (String) names.nextElement();
			String value = request.getParameter(name);
			if (res != "")
			{
				res += "&"; 
			}
			res += name + "=" + value;
		}
		return res != ""  ? "?" + res : "";
	}

	private String filterResponse(String target, HttpServletRequest request,
			Map<String, String> responseProps, String responseText) throws UnsupportedEncodingException
	{
		if (target.equals("login.php") || target.startsWith("http://api.recaptcha.net") || target.startsWith("http://www.google.com/recaptcha/api"))
		{
			String responseText2= responseText.replaceAll("api.recaptcha.net", m_host + ":" + m_port + "/proxy/external:http://api.recaptcha.net");
			responseText2= responseText2.replaceAll("www.google.com/recaptcha/api", m_host + ":" + m_port + "/proxy/external:http://www.google.com/recaptcha/api");
			responseText = responseText2;
		}
		return responseText;
	}
	
	public void setUpdateCategories(String s) throws FileNotFoundException, IOException
	{
		Log.info("New update categories : " + s);
		m_updateCategories = s;
		Iterator<String> keys = m_torrents.keySet().iterator();
		while(keys.hasNext())
		{
			String id = keys.next();
			Torrent t = m_torrents.get(id);
			if (!categoryActive(t.cat))
				keys.remove();
		}
		
		m_lastUpdate = 0;
		synchronized (TorrentLeechRssServer.this)
		{
			TorrentLeechRssServer.this.notifyAll();
		}
		saveOptions();
	}
	
	public void saveOptions() throws IOException
	{
		Properties props = new Properties();
		File file = new File("conf.properties");
		props.load(new FileInputStream(file));
		props.put("update_categories", m_updateCategories);
	}
	
	public boolean categoryActive(int id)
	{
		return m_updateCategories.contains(""+id);
	}
}
