package net.firefang.tl2rss;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import org.mortbay.log.Log;

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
	private Map<String, Torrent> m_torrents = new HashMap<String, Torrent>();
	
	private String m_host;
	private int m_port;
	
	private int m_updateInterval;

	private String m_updateCategories;

	protected long m_torrentTimeoutDays;
	
	Map<String,String> m_cookies = new HashMap<String, String>();
	
	public TorrentLeechRssServer(Properties props) throws Exception
	{
		loadCookies();
		
		m_updateCategories = props.getProperty("update_categories", "7");
		m_host = props.getProperty("host", "localhost");
		m_port = Integer.parseInt(props.getProperty("port", "8080"));
		m_torrentTimeoutDays = Integer.parseInt(props.getProperty("torrent_timeout_days", "7"));
		
		
		m_updateInterval = Integer.parseInt(props.getProperty("update_interval", "25"));
		
		Server server = new Server(m_port);
		server.setHandler(new DefaultHandler()
		{
			public void handle(String target, HttpServletRequest request,
					HttpServletResponse response, int dispatch) throws IOException,
					ServletException
			{
		        if (target.startsWith("/proxy/"))
		        {
		        	proxy(request, response, target.substring("/proxy/".length()));
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
				}
				else
				{
		        	if (!isAuthenticated())
		        	{
		        		tl(request, response);
		        	}
		        	else
		        	{
		        		response.setContentType("text/xml");
		        		try
		        		{
		        			response.getWriter().write(getRSS());
		        		} catch (FeedException e)
		        		{
		        			throw new ServletException(e);
		        		}
		        	}
				}
		        
		        ((Request)request).setHandled(true);
			}
		});
		
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
	
	protected void tl(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		//String html = "<html><head><base href='http://www.torrentleech.org'/><base target='_blank'/></head><body> <frame src='http://localhost:8080/proxy/'></frame></body></html>";
		String html = "<html>\n" +
				"\t<head>\n" +
				"\t\t<base href='http://www.torrentleech.org'/>\n" +
				"\t</head>\n" +
				"\t<body>\n" +
				"Please log into TorrentLeech<br/>" +
				"Note: TL2RSS does not do anything with your username and password except sending them to TorrentLeech. however, cookies are stored in cookies.txt to avoid further logins<br/>\n" +
				"After you login, you should be able to use your TL2RSS URL in your RSS reader<br/>\n" +
				"\t\t<iframe width='100%' height='100%' src='http://localhost:8080/proxy/'></iframe>\n" +
				"\t</body>\n" +
				"</html>\n";
		response.setContentType("text/html");
		response.getWriter().write(html);
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
					for (String id : m_torrents.keySet())
					{
						Torrent t = m_torrents.get(id);
						if (now - t.creationTime > (m_torrentTimeoutDays * 24 * 60 * 60 * 1000));
						{
							Log.info("Torrent expired, removing " + t.name);
							m_torrents.remove(id);
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
		Thread t = new Thread("Torrents update thread")
		{
			public void run()
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
					} catch (IOException e1)
					{
						Log.info("IOException when updating torrents",e1);
					}
					
					try
					{
						Thread.sleep(m_updateInterval * 60 * 1000);
					} catch (InterruptedException e)
					{
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}
	
	long m_lastUpdate;
	protected void updateTorrents() throws IOException
	{
		if (System.currentTimeMillis() - m_lastUpdate < (m_updateInterval-1) * 60 * 1000) return;
		
		StringTokenizer tok = new StringTokenizer(m_updateCategories, ", ");
		while(tok.hasMoreElements())
		{
			String cat = tok.nextToken();
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
	public String getRSS() throws FeedException
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


	
//	private void test() throws FileNotFoundException, ParserException, UnsupportedEncodingException
//	{
//		processTorrentsStream(new FileInputStream("test.html"));
//
//	}

	private Torrent getTorrent(String id)
	{
		if (!m_torrents.containsKey(id))
		{
			m_torrents.put(id, new Torrent(id));
		}
		
		return (Torrent) m_torrents.get(id);
	}

	private void updateCategory(String cat, boolean firstRun) throws ParserException, IOException 
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
			Log.warn("Updating torrents : " + url);
			URLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
			conn.setRequestProperty("Cookie", getCookiesString());
			InputStream in = conn.getInputStream();
			try
			{
				processTorrentsStream(in);
			}
			finally
			{
				in.close();
			}
		}
	}
	
	private void processTorrentsStream(InputStream in) throws UnsupportedEncodingException, ParserException
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
								torrent = getTorrent(id);
								
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
									torrent = getTorrent(id);
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
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
		return conn.getInputStream();
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
	
	private static class Torrent
	{
		String id; 
		String name;
		String date;
		String downloadLink;
		long creationTime;
		
		public Torrent(String id)
		{
			creationTime = System.currentTimeMillis();
			this.id = id;
		}
		
		public String toString()
		{
			return id + " " + name + ", " + date + ", " + downloadLink;
		}
	}
	

	private void handleCookies(List<String> cookies) throws IOException
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
			updateTorrents();
			synchronized (this)
			{
				notifyAll();
			}
		}
	}
	
	public boolean isAuthenticated()
	{
		String uid = m_cookies.get("uid");
		if (uid == null) return false;
		try
		{
			Integer.parseInt(uid);
			return true;
		} catch (NumberFormatException e)
		{
			return false;
		}
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
		new TorrentLeechRssServer(props);
	}
	
	@SuppressWarnings("unchecked")
	void proxy(HttpServletRequest request, HttpServletResponse response, String target) throws IOException
	{
		HttpURLConnection.setFollowRedirects(false);
		String host = "www.torrentleech.org";
		URL url = new URL("http://"+host+"/" + target);
		Log.info("proxying to " + url);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		Enumeration headers = request.getHeaderNames();
		while(headers.hasMoreElements())
		{
			String k = (String) headers.nextElement();
			String v = request.getHeader(k);
			if (k.equals("Host"))
			{
				v = host;
			}
			if (k.equals("Referer"))
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
					v = "http://" + host + v.substring(i2 + "/proxy/".length());
				}
			}
			
//			Log.info("request header: " + k + "=" + v);
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
//	        Log.info("Sent " + sb);
		}
		
		int code = conn.getResponseCode();
		InputStream in = conn.getInputStream();
		
		List<String> cookies = (List<String>) conn.getHeaderFields().get("Set-Cookie");
		handleCookies(cookies);
		
		if (!isAuthenticated())
		{
			response.setStatus(code);
			int i = 1;
			while(true)
			{
				String k = conn.getHeaderFieldKey(i);
				if (k == null) break;
				String v = conn.getHeaderField(i);
				i++;
				response.setHeader(k, v);
//				Log.info("response header: " + k + "=" + v);
			}
			
			
			OutputStream out = response.getOutputStream();
			BufferedInputStream bin = new BufferedInputStream(in, 4096);
			byte buff[] = new byte[4096];
			int len;
			StringBuffer sb = new StringBuffer();
			while((len = bin.read(buff)) != -1) 
			{
				out.write(buff, 0, len);
				sb.append(new String(buff, 0, len));
			}
//			Log.info("Received " + sb);
		}
		else
		{
//			Log.info("Authenticated");
			response.sendRedirect("/");
		}
	}	
}
