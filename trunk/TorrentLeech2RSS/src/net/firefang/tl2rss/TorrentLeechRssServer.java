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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
	private String m_password;
	private String m_username;
	
	public TorrentLeechRssServer(Properties props) throws Exception
	{
		HttpURLConnection.setFollowRedirects(false);
		instance = this;
		loadCookies();
		loadCategories();
		
		m_updateCategories = props.getProperty("update_categories", "7");
		m_host = props.getProperty("host", InetAddress.getLocalHost().getHostName());
		m_port = Integer.parseInt(props.getProperty("port", "8080"));
		m_torrentTimeoutDays = Integer.parseInt(props.getProperty("torrent_timeout_days", "7"));
		m_password = props.getProperty("password");
		m_username = props.getProperty("username");
		
		// prevent usage of old cookie if we don't have a stored username and password.
		if (m_password == null || m_username == null)
			logout();
		
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

	void loadCookies()
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
							if (!isAuthenticated())
							{
								authenticate();
							}
							
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
	
	protected void authenticate() 
	{
		if (m_username != null && m_password != null)
		{
			try 
			{
				logger.info("Attempting to login with saved credentials");
				
//				doGet("http://www.torrentleech.org/").close();
				
				URL url = new URL("http://www.torrentleech.org/user/account/login/");
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setDoOutput(true);
				conn.setRequestMethod("POST");
				conn.setRequestProperty("Cookie", getCookiesString());
				
				OutputStream out = conn.getOutputStream();
				String post = "username=" + URLEncoder.encode(m_username, "utf-8") + "&password=" + URLEncoder.encode(m_password, "utf-8") + "&login=submit&remember_me=on";
				out.write(post.getBytes());
				
				int res = conn.getResponseCode();
				if (res == 200 || res == 302)
				{
					handleCookies(getConnCookies(conn));				
				}
				
				if (!isAuthenticated())
				{
					m_username = null;
					m_password = null;
				}
			} 
			catch (IOException e) 
			{
				logger.warn("Error loging in", e);
			} 
		}
	}

	long m_lastUpdate;
	private Thread m_updateThread;
	protected void updateTorrents() throws IOException
	{
		if (System.currentTimeMillis() - m_lastUpdate < (m_updateInterval-1) * 60 * 1000) return;
		logger.info("Updating torrents");

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

	@SuppressWarnings({ "unchecked", "rawtypes" })
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
		List<SyndEntry> entries = new ArrayList<SyndEntry>();
		feed.setEntries(entries);
		
		Map<Integer, Integer> cat_count = new HashMap<Integer, Integer>();
		Vector<Torrent> v = new Vector<Torrent>();
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
                                long d = b.date - a.date;
                                return d > 0 ? 1 : d < 0 ? -1 : 0;
			}
		});
		
		
		for (int i = 0; i < v.size(); i++)
		{
			Torrent t = v.elementAt(i);
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
				List<SyndCategory> syndCategories = new ArrayList<SyndCategory>();
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
				
//				entry.setLink(baseUrl + "/download/" +  t.id + ".torrent");
				try 
				{
					String link = baseUrl + "/proxy" + new URL(t.downloadLink).getPath();
					entry.setLink(link);
				} 
				catch (MalformedURLException e) 
				{
					logger.error(e);
				}
				entry.setPublishedDate(new Date(t.date));
				entries.add(entry);
			}
			
		}

		SyndFeedOutput output = new SyndFeedOutput();
		return output.outputString(feed);
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

		for(int i=0;i<urls.size();i++)
		{
			URL url = new URL(urls.get(i));
			logger.info("Updating torrents : " + url);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
			conn.setRequestProperty("Cookie", getCookiesString());
			conn.setRequestProperty("Host", "www.torrentleech.org");
			/*int resp = */conn.getResponseCode();
			InputStream in = conn.getInputStream();
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			pump(in, bout);
			
			byte data[] = bout.toByteArray();
    	    List<String> cookies = getConnCookies(conn);
	        boolean authenticated = handleCookies(cookies);
			if (!authenticated) 
			{
				i--;
				if (!handleDisconnection())
					return;
			}

			String html = new String(data, "UTF-8");
			int detectedTorretnts = processTorrentsStream(html);
			if (detectedTorretnts == 0)
			{
				i--;
				if (!handleDisconnection())
					return;
			}
			
			try
			{
				Thread.sleep(2500);
			} catch (InterruptedException e)
			{
			}			
		}
	}

	private List<String> getConnCookies(HttpURLConnection conn) 
	{
		List<String> res = new ArrayList<String>();
		int n=0;
		while(conn.getHeaderField(n) != null)
		{
			if ("Set-Cookie".equals(conn.getHeaderFieldKey(n)))
				res.add(conn.getHeaderField(n));
			n++;
		}
		return res;
	}

	private void pump(InputStream in, OutputStream bout)throws IOException 
	{
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
	}

	private void traceCookies() 
	{
		Iterator<String> keys = m_cookies.keySet().iterator();
		logger.trace("Cookies : ");
		while(keys.hasNext())
		{
			String key = keys.next();
			String value = m_cookies.get(key);
			logger.trace(">\t" + key + " = " + value);
		}
	}

	private boolean handleDisconnection() throws IOException 
	{
		authenticate();
		boolean b = isAuthenticated();
		if (!b)
		{
			logger.warn("No longer authenticated, clearing credentials and cookies");
			logout();
		}
		return b;
	}

	private void logout() throws IOException 
	{
		m_username = null;
		m_password = null;
		saveCreds();
		clearCookies();
	}

	private void clearCookies() {
		m_cookies.clear();
		saveCookies();
	}
	
	//2010-05-16 05:28:55
	static DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); 
	static Pattern catLink = Pattern.compile("/torrents/browse/index/categories/([0-9]+)");
	private int processTorrentsStream(String html) throws UnsupportedEncodingException, ParserException
	{
		int detectTorrents = 0;
		Parser parser = new Parser(new Lexer(new Page(html, "UTF-8")), Parser.DEVNULL);
		NodeList res = parser.extractAllNodesThatMatch(getDownloadsFilter());
		for (int i = 0; i < res.size(); i++)
		{
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
								
								if (!m_torrents.containsKey(id))
								{
									m_torrents.put(id, new Torrent(id, category));
								}
								
								detectTorrents++;
								torrent = m_torrents.get(id);
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
							TextNode tt = (TextNode) href.getChild(0);
							if (tt != null)
							{
								torrent.name = tt.getText();
								torrent.downloadLink =  "http://www.torrentleech.org/download/" + torrent.id + "/" + java.net.URLEncoder.encode(torrent.name + ".torrent", "utf-8");
							}
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
		return detectTorrents;
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
				logout();
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
		logger.trace("Setting cookies :");
		for(int i=0;cookies != null && i<cookies.size();i++)
		{
			String s = cookies.get(i);
			StringTokenizer t = new StringTokenizer(s, "="); // key=value;exp;location
			String key = t.nextToken();
			StringTokenizer toks = new StringTokenizer(t.nextToken(),";");
			String value = toks.nextToken();
			if (value.equals("deleted"))
			{
				logger.trace("-\tdeleting " + key);
				m_cookies.remove(key);
			}
			else
			{
				String old = m_cookies.put(key, value);
				if (!value.equals(old))
					logger.trace("<\t" + key + " = " + value);
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
		if (m_cookies.get("member_id")== null) return false;
		return true;
	}
	
	private String getCookiesString()
	{
		// touch timestamps
		m_cookies.put("lastBrowse1", "" + (System.currentTimeMillis() / 1000));
		m_cookies.put("lastBrowse2", "" + (System.currentTimeMillis() / 1000));

		StringBuffer b = new StringBuffer();
		Iterator<String> keys = m_cookies.keySet().iterator();
		while(keys.hasNext())
		{
			String key = keys.next();
			String value = m_cookies.get(key);
			b.append(key + "=" + value);
			if (keys.hasNext())
			{
				b.append(";");
			}
		}
		
		traceCookies();
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
	
	void proxy(HttpServletRequest request, HttpServletResponse response, String target) throws IOException
	{
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
		
		boolean isDownload = target.startsWith("download/");
		
		logger.debug("proxying to " + url);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
		@SuppressWarnings("rawtypes")
		Enumeration headers = request.getHeaderNames();
		while(headers.hasMoreElements())
		{
			String k = (String) headers.nextElement();
			String v = request.getHeader(k);
			if (k.equals("Cookie") || k.equals("Accept-Encoding"))
			{
				logger.trace("Skipping "  + k + " = " + v);
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
					int i3 = v.indexOf("/rss");
					if (i != -1)
					{
						v = "http://" + host + v.substring(i + "/torrentleech".length());
					}
					else
					if (i2 != -1)
					{
						v = "http://" + host + "/" + v.substring(i2 + "/proxy/".length());
					}
					else
					if (i3 != -1)
					{
						v = "http://" + host + "/" + v.substring(i3 + "/rss".length());
					}
				}
				else
				{
					v = referrer;
				}
			}
			
			logger.trace("request header: " + k + "=" + v);
			conn.addRequestProperty(k, v);
		}
		
		boolean grabPassword = false;
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
	        
			if (target.equals("user/account/login/"))
			{
				Properties postData = getPostData(sb);
				m_username = postData.getProperty("username");
				m_password = postData.getProperty("password");
				grabPassword = true;
			}
		}
		
		int code = conn.getResponseCode();
		InputStream in = conn.getInputStream();
		
		List<String> cookies = getConnCookies(conn);
		if (!isDownload)
			handleCookies(cookies);
		
		if (!isAuthenticated() || isDownload)
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
				logger.trace("response header: " + k + "=" + v);
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
		}
		else
		{
			if (grabPassword)
			{
				saveCreds();
			}
			logger.info("Authenticated");
			response.sendRedirect("/");
		}
	}

	private Properties getPostData(StringBuffer sb) throws UnsupportedEncodingException 
	{
		Properties u = new Properties();
		StringTokenizer t = new StringTokenizer(sb.toString(), "&");
		while(t.hasMoreElements())
		{
			String e = t.nextToken();
			StringTokenizer s = new StringTokenizer(e, "=");
			String k = URLDecoder.decode(s.nextToken(), "utf-8");
			String v = null;
			if (s.hasMoreElements())
			{
				v = URLDecoder.decode(s.nextToken(), "utf-8");
			}
			u.setProperty(k, v);
		}
		return u;
		
	}

	private void saveCreds() throws IOException 
	{
		Properties props = new Properties();
		if (m_username != null && m_password != null)
		{
			props.setProperty("username", m_username);
			props.setProperty("password", m_password);
			saveOptions(props);
		}
	}

	private String getParameters(HttpServletRequest request)
	{
		String res = "";
		@SuppressWarnings("rawtypes")
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
		saveOptions(new Properties());
	}
	
	public void saveOptions(Properties extra) throws IOException
	{
		Properties props = new Properties();
		File file = new File("conf.properties");
		props.load(new FileInputStream(file));
		props.putAll(extra);
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
	
	public String getUpdatedCategories()
	{
		String res = "";
		StringTokenizer tok = new StringTokenizer(m_updateCategories, ", ");
		while(tok.hasMoreElements())
		{
			int cat = Integer.parseInt(tok.nextToken());
			if (res != "") res += ",";
			res += getCategory(cat);
		}
		
		return res;
	}
	
	public int numTorrents()
	{
		return m_torrents.size();
	}
}
