package net.firefang.tl2rss;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.DateFormat;
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
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.htmlparser.Node;
import org.htmlparser.NodeFilter;
import org.htmlparser.Parser;
import org.htmlparser.Tag;
import org.htmlparser.beans.FilterBean;
import org.htmlparser.filters.AndFilter;
import org.htmlparser.filters.HasAttributeFilter;
import org.htmlparser.filters.HasChildFilter;
import org.htmlparser.filters.HasSiblingFilter;
import org.htmlparser.filters.NodeClassFilter;
import org.htmlparser.filters.NotFilter;
import org.htmlparser.filters.TagNameFilter;
import org.htmlparser.lexer.Lexer;
import org.htmlparser.lexer.Page;
import org.htmlparser.nodes.TextNode;
import org.htmlparser.tags.CompositeTag;
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
import org.mortbay.resource.Resource;

import com.sun.syndication.feed.synd.SyndCategory;
import com.sun.syndication.feed.synd.SyndCategoryImpl;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndEntryImpl;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.feed.synd.SyndFeedImpl;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedOutput;

/**
 * @author omry
 */
public class TorrentLeechRssServer
{
	private static Logger logger;
	public static TorrentLeechRssServer instance;
	private Map<String, Torrent> m_torrents = new HashMap<String, Torrent>();
	
	private String m_host;
	private int m_port;
	
	private int m_updateInterval;

	private String m_updateCategories;

	protected long m_torrentTimeoutDays;
	
	Map<String,String> m_cookies = new HashMap<String, String>();
	
	List<CategoryGroup> m_catGroups;
	Map<Integer, String> m_categories;
	
	public TorrentLeechRssServer(Properties props) throws Exception
	{
		instance = this;
		loadCookies();
		loadCategories();
		
		m_updateCategories = props.getProperty("update_categories", "7");
		m_host = props.getProperty("host", InetAddress.getLocalHost().getHostName());
		m_port = Integer.parseInt(props.getProperty("port", "8080"));
		m_torrentTimeoutDays = Integer.parseInt(props.getProperty("torrent_timeout_days", "7"));
		logger.info("Running from " + m_host + ":" + m_port);
		
		m_updateInterval = Integer.parseInt(props.getProperty("update_interval", "25"));
		
		Server server = new Server(m_port);
		server.setHandler(new Handler());
        Context root = new Context(server, "/", Context.SESSIONS);
        root.setBaseResource(Resource.newResource(new File("jsp").getAbsolutePath() , false));
//        root.addServlet(new ServletHolder(new DefaultServlet()), "/");
        root.addServlet(new ServletHolder(new DefaultServlet()), "/res/*");
		ServletHolder jspholder = new ServletHolder(new JspServlet());
		jspholder.setInitParameter("scratchdir", new File("compiled_jsp").getAbsolutePath());
		root.addServlet(jspholder, "*.jsp");
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
	
	private void loadCategories() throws IOException
	{
		m_categories = new HashMap<Integer, String>();
		m_catGroups = new ArrayList<CategoryGroup>();
		Pattern groupMatch = Pattern.compile("\\[(.)*\\]");
		Pattern categoryMatch = Pattern.compile("([0-9]+)=(.*)");
		BufferedReader b = new BufferedReader(new InputStreamReader( new FileInputStream("categories.txt")));
		
		try
		{
			CategoryGroup currentGroup = null;
			String line = null;
			while((line = b.readLine()) != null)
			{
				Matcher gm = groupMatch.matcher(line);
				if (gm.matches())
				{
					String group = line.substring(1, line.length() - 1);
					currentGroup = new CategoryGroup(group);
					m_catGroups.add(currentGroup);
				}
				else
				{
					Matcher matcher = categoryMatch.matcher(line);
					if (matcher.matches())
					{						
						int category = Integer.parseInt(matcher.group(1));
						String desc = matcher.group(2);
						m_categories.put(category, desc);
						currentGroup.categories.add(new Category(category, desc));
					}
				}
			}
		}
		finally
		{
			b.close();
		}
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
				if (!"JSESSIONID".equals(key))
					m_cookies.put(key, value);
				logger.info("Loaded cookie " + key + "=" + value);
			}
		} catch (IOException e)
		{
			logger.warn(e);
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
			logger.warn(e);
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
							logger.info("Torrent expired, removing " + t.name);
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
										logger.info("Waiting for authentication");
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
							logger.info("IOException when updating torrents",e1);
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
					logger.info("Update thread exited");
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
		logger.info("Updating torrents");
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
				logger.warn("Error parsing html of category " + cat,e);
			}
		}
		m_lastUpdate = System.currentTimeMillis();
	}

	@SuppressWarnings("unchecked")
	public String getRSS(Set<Integer> cats, int max_per_cat) throws FeedException
	{
		String feedType = "rss_2.0";
		SyndFeed feed = new SyndFeedImpl();
		feed.setEncoding("UTF-8");
		feed.setFeedType(feedType);

		feed.setTitle("TorrentLeech RSS Feed");
		String baseUrl = "http://" + m_host + (m_port != 80? ":"+m_port :"" );
		feed.setLink(baseUrl);
		feed.setDescription("This feed have been craeted by TorrentLeech2RSS, see http://tl2rss.firefang.net for more info");
		List entries = new ArrayList();
		feed.setEntries(entries);
		
		Map<Integer, Integer> cat_count = new HashMap<Integer, Integer>();
		Vector v = new Vector();
		for(Torrent t : m_torrents.values())
		{
			v.add(t);
		}
		
		Collections.sort(v, new Comparator()
		{
			public int compare(Object t1, Object t2)
			{
				Torrent a = (Torrent) t1;
				Torrent b = (Torrent) t2;
				return (int) (b.date - a.date);
			}
		});
		
		
		for (int i = 0; i < v.size(); i++)
		{
			Torrent t = (Torrent) v.elementAt(i);
			if (cats == null || cats.contains(t.cat))
			{
				Integer count = cat_count.get(t.cat);
				if (count == null)
					count = 0;
				
				if (max_per_cat == count)
					continue;
				count = count + 1;
				cat_count.put(t.cat, count);
				
				SyndEntry entry = new SyndEntryImpl();
				try 
				{
					entry.setTitle(new String(t.name.getBytes("UTF-8")));
				}
				catch (UnsupportedEncodingException e) 
				{
				}
				
				String catName = m_categories.get(t.cat);
				List syndCategories = new ArrayList();
				SyndCategory allCategory = new SyndCategoryImpl();
				allCategory.setName("All Categories"); 
				syndCategories.add(allCategory);
				if (catName != null)
				{
					SyndCategory syndCategory = new SyndCategoryImpl();
					syndCategory.setName(catName); 
					syndCategories.add(syndCategory);
				}
				entry.setCategories(syndCategories);
				
				entry.setLink(baseUrl + "/download/" +  t.id + ".torrent");
				entry.setPublishedDate(new Date(t.date));
				entries.add(entry);
			}
			
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
		urls.add("http://www.torrentleech.org/torrents/browse/index/categories/"+cat+"/page/1");
		if (firstRun) // grab a few more additional pages
		{
			urls.add("http://www.torrentleech.org/torrents/browse/index/categories/"+cat+"/page/2");
			urls.add("http://www.torrentleech.org/torrents/browse/index/categories/"+cat+"/page/3");
			urls.add("http://www.torrentleech.org/torrents/browse/index/categories/"+cat+"/page/4");
		}

		for(String u : urls)
		{
			URL url = new URL(u);
			logger.info("Updating torrents : " + url);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
			conn.setRequestProperty("Cookie", getCookiesString());
			conn.setRequestProperty("Host", "www.torrentleech.org");
			int resp = conn.getResponseCode();
			logger.debug("Response code : " + resp);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			InputStream in = conn.getInputStream();
			try
			{
				BufferedInputStream bin = new BufferedInputStream(in);
				int c;
				while((c = bin.read()) != -1)
				{
					bout.write(c);
				}
			}
			finally
			{
				in.close();
			}
			
			byte data[] = bout.toByteArray();
    	    List<String> cookies = (List<String>) conn.getHeaderFields().get("Set-Cookie");
	        boolean authenticated = handleCookies(cookies);
			if (!authenticated) 
			{
				logger.warn("No longer authenticated, aborting torrents update");
				m_cookies.clear();
				saveCookies();
				return;
			}

			processTorrentsStream(new ByteArrayInputStream(data));
			
			try
			{
				Thread.sleep(2500);
			} catch (InterruptedException e)
			{
			}			
		}
	}
	
	//2010-05-16 05:28:55
	static DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); 
	static Pattern catLink = Pattern.compile("/torrents/browse/index/categories/([0-9]+)");
	private void processTorrentsStream(InputStream in) throws UnsupportedEncodingException, ParserException
	{
		Parser parser = new Parser(new Lexer(new Page(in, "UTF-8")), Parser.DEVNULL);
		NodeList res = parser.extractAllNodesThatMatch(getDownloadsFilter());
		for (int i = 0; i < res.size(); i++)
		{
			
			
			/*
			 * Extract:
 			String id; 
			String name;
			long date;
			String downloadLink;
			long creationTime;
			int cat;
			*/
			
			Node node = res.elementAt(i);
			NodeList children = node.getChildren();
			String id = ((Tag)node).getAttribute("id");
			int category = -1;
			Torrent torrent = null;
			for (int j = 0; j < children.size(); j++)
			{
				Node c = children.elementAt(j);
				if (c instanceof TableColumn)
				{
					TableColumn td = (TableColumn) c;
					if ("category".equals(((Tag)c).getAttribute("class")))
					{
						if (td.getChildCount() == 1 && td.childAt(0) instanceof LinkTag)
						{
							String link = ((LinkTag)td.childAt(0)).extractLink();
							Matcher matcher = catLink.matcher(link);
							if (matcher.matches())
							{						
								category = Integer.parseInt(matcher.group(1));
								torrent = getTorrent(id, category);
							}
						}
					}
					else
					if ("name".equals(((Tag)c).getAttribute("class")))
					{
						CompositeTag title = (CompositeTag) td.childAt(0);
						if (title.childAt(0) instanceof LinkTag)
						{
							LinkTag href = (LinkTag) title.childAt(0);
							torrent.name = ((TextNode) href.getChild(0)).getText(); 
						}
						
						NodeList ch = c.getChildren();
						for (int k = 0; k < ch.size(); k++)
						{
							Node created = ch.elementAt(k);
							if (created instanceof TextNode)
							{
								TextNode createdt = (TextNode) created;
								String str = createdt.getText();
								if (str.startsWith(" on "))
								{
									String d = str.substring(" on ".length());
									Date dd;
									try
									{
										dd = df.parse(d);
									} catch (ParseException e)
									{
										logger.warn("Error parsing " + d + " : " + e.getClass().getName() + " : " + e.getMessage());
										dd = new Date();
									}
									torrent.date = dd.getTime();
									
								}
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
        TagNameFilter filter0 = new TagNameFilter ();
        filter0.setName ("TR");
        HasAttributeFilter filter1 = new HasAttributeFilter ();
        filter1.setAttributeName ("id");
        TagNameFilter filter2 = new TagNameFilter ();
        filter2.setName ("TD");
        HasAttributeFilter filter3 = new HasAttributeFilter ();
        filter3.setAttributeName ("class");
        filter3.setAttributeValue ("category");
        NodeFilter[] array0 = new NodeFilter[2];
        array0[0] = filter2;
        array0[1] = filter3;
        AndFilter filter4 = new AndFilter ();
        filter4.setPredicates (array0);
        HasChildFilter filter5 = new HasChildFilter ();
        filter5.setRecursive (false);
        filter5.setChildFilter (filter4);
        NodeFilter[] array1 = new NodeFilter[3];
        array1[0] = filter0;
        array1[1] = filter1;
        array1[2] = filter5;
        AndFilter filter6 = new AndFilter ();
        filter6.setPredicates (array1);
        NodeFilter[] array2 = new NodeFilter[1];
        array2[0] = filter6;
        FilterBean bean = new FilterBean ();
        bean.setFilters (array2);
        
        AndFilter finalFilter = new AndFilter ();
        finalFilter.setPredicates (array2);
        return finalFilter;
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
			if (target.startsWith("/download/") && target.endsWith(".torrent"))
			{
				String id = target.substring("/download/".length(), target.length() - ".torrent".length());
		        Torrent t = m_torrents.get(id);
		        if (t == null)
		        {
		        	response.sendError(404, "Unknown torrent");
		        }
		        else
		        {
		        	
		        	InputStream in = doGet(t.downloadLink);
		        	response.setContentType("application/x-bittorrent");
		        	OutputStream out = response.getOutputStream();
		        	int c;
		        	while((c = in.read()) != -1) out.write(c);
		        }
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
						

						int max = -1;
						String max_per_cat  = request.getParameter("max_per_cat");
						if (max_per_cat != null) max = Integer.parseInt(max_per_cat);
						
						response.getWriter().write(getRSS(cats, max));
					}
					catch (FeedException e)
					{
						throw new ServletException(e);
					}
				}
				else
				{
		    		response.setContentType("text/html");
		    		response.getWriter().write("Not authenticated, you need to <a href='/proxy/user/account/login/'>Login</a>");
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
				((Request)request).setHandled(true);
			}
		}
	}

	private static class Torrent
	{
		String id; 
		String name;
		long date;
		String downloadLink;
		long creationTime;
		int cat;
		
		public Torrent(String id, int c)
		{
			cat = c;
			creationTime = System.currentTimeMillis();
			this.id = id;
			downloadLink = "http://www.torrentleech.org/download/" + id;
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
				if (!"JSESSIONID".equals(key))
					m_cookies.put(key, value);
			}
		}

		saveCookies();
		
		if (!wasAuthenticated && isAuthenticated())
		{
			logger.info("Authenticated, updateing torrents");
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
//		return false;
		if (m_cookies.get("member_id")== null) return false;
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
		DOMConfigurator.configure("log4j.xml");
		logger = Logger.getLogger(TorrentLeechRssServer.class);
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
			logger.info("Created a new conf.propeties file");
		}
		props.load(new FileInputStream(file));
		new TorrentLeechRssServer(props);
	}
	
	@SuppressWarnings("unchecked")
	void proxy(HttpServletRequest request, HttpServletResponse response, String target) throws IOException
	{
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
		
		logger.debug("proxying to " + url);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
		Enumeration headers = request.getHeaderNames();
		while(headers.hasMoreElements())
		{
			String k = (String) headers.nextElement();
			String v = request.getHeader(k);
			if (k.equals("Accept-Encoding") || k.equals("Cookie"))
			{
				logger.debug("Skipping "  + k + " = " + v);
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
			
			logger.debug("request header: " + k + "=" + v);
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
//	        if (debug) logger.info("Sent " + sb);
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
				logger.debug("response header: " + k + "=" + v);
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
					String tlhttp = "http://www.torrentleech.org/";
					if (v.startsWith(tlhttp))
					{
						String path = v.substring(tlhttp.length());
						v = "http://"+ m_host + ":" + m_port + "/proxy/"+path;
					}
					else
					if (v.equals("/"))
					{
						v = "http://"+ m_host + ":" + m_port + "/proxy/";
					}
					else
						v = "http://"+ m_host + ":" + m_port + "/proxy/external:" + v;
				}
				response.setHeader(k, v);
			}
			response.setContentLength(data.length);
			OutputStream out = response.getOutputStream();
			out.write(data);
//			if (debug) logger.info("Received " + sb);
		}
		else
		{
			logger.info("Authenticated");
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
		if (target.startsWith("") || target.startsWith("http://api.recaptcha.net") || target.startsWith("http://www.google.com/recaptcha/api"))
		{
			String responseText2= responseText.replaceAll("api.recaptcha.net", m_host + ":" + m_port + "/proxy/external:http://api.recaptcha.net");
			responseText2= responseText2.replaceAll("www.google.com/recaptcha/api", m_host + ":" + m_port + "/proxy/external:http://www.google.com/recaptcha/api");
			responseText2= responseText2.replaceAll("/user", "/proxy/user");
			responseText = responseText2;
		}
		return responseText;
	}
	
	public void setUpdateCategories(String s) throws FileNotFoundException, IOException
	{
		synchronized (m_torrents)
		{
			Iterator<String> keys = m_torrents.keySet().iterator();
			while(keys.hasNext())
			{
				String id = keys.next();
				Torrent t = m_torrents.get(id);
				if (!categoryActive(t.cat))
					keys.remove();
			}
		}
		
		StringTokenizer tok = new StringTokenizer(s, ", ");
		boolean all = false;
		while(tok.hasMoreElements())
		{
			int cat = Integer.parseInt(tok.nextToken());
			if (cat == 0) all = true;
		}
		
		if (all)
		{
			if (tok.countTokens() > 1)
			{
				logger.info("Reseting categories list to 0 only (all)");
				s = "0";
			}
		}
		
		logger.info("New update categories : " + s);
		m_updateCategories = s;
		m_lastUpdate = 0;
		m_torrents.clear();
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
		FileOutputStream f = new FileOutputStream(file);
		try
		{
			props.store(f, "Updated by TL2RSS");
		}
		finally
		{
			f.close();
		}
	}
	
	public boolean categoryActive(int id)
	{
		return m_updateCategories.contains(""+id);
	}
	
	public String getCategory(int id)
	{
		return m_categories.get(id);
	}
	
	public Iterator<CategoryGroup> catGroups()
	{
		return m_catGroups.iterator();
	}
}
