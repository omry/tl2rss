package net.firefang.tl2rss;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Hashtable;
import java.util.List;
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
 * todo: expire user sessions.
 */

/**
 * @author omry
 */
public class TorrentLeech
{
	private Hashtable m_torrents = new Hashtable();
	
	private Hashtable m_userSessions;
	
	private String m_host;
	private int m_port;
	
	private int m_updateInterval;
	private String m_systemUser;
	private String m_systemPassword;

	private String m_updateCategories;
	
	public TorrentLeech(Properties props) throws Exception
	{
		m_userSessions = new Hashtable();
		m_systemUser = props.getProperty("system_user");
		m_systemPassword = props.getProperty("system_pass");
		m_updateCategories = props.getProperty("update_categories", "7");
		m_host = props.getProperty("host", "localhost");
		m_port = Integer.parseInt(props.getProperty("port", "8080"));
		m_updateInterval = Integer.parseInt(props.getProperty("update_interval", "10"));
		startUpdateThread();
		
		
		
		Server server = new Server(m_port);
		server.setHandler(new DefaultHandler()
		{
			public void handle(String target, HttpServletRequest request,
					HttpServletResponse response, int dispatch) throws IOException,
					ServletException
			{
		        String user = request.getParameter("user");
		        if (user == null) throw new ServletException("Missing user parameter");
		        String pass = request.getParameter("pass");
		        if (pass == null) throw new ServletException("Missing pass parameter");
		        
		        UserSession session = (UserSession) m_userSessions.get(user + ":::" + pass);
		        if (session == null)
		        {
		        	m_userSessions.put(user + ":::" + pass, session = new UserSession(user, pass));
		        }
		        
		        synchronized(session)
		        {
		        	if (!session.isAuthenticated())
		        	{
		        		if (!session.authenticate())
		        		{
		        			((Request)request).setHandled(true);
		        			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		        			response.getWriter().write("Not authorized");
		        			return;
		        		}
		        	}
		        }
		        
				if (target.equals("/download"))
				{
			        String id = request.getParameter("id");
			        if (id == null) throw new ServletException("Missing id parameter");
			        Torrent t = (Torrent) m_torrents.get(id);
			        InputStream in = doGet(t.downloadLink, session);
			        response.setContentType("application/x-bittorrent");
			        OutputStream out = response.getOutputStream();
			        int c;
			        while((c = in.read()) != -1) out.write(c);
				}
				else
				{
			        response.setContentType("text/xml");
			        try
					{
						response.getWriter().write(getRSS(session));
					} catch (FeedException e)
					{
						throw new ServletException(e);
					}
				}
		        
		        ((Request)request).setHandled(true);
			}
		});
		server.start();
	}
	
	private void startUpdateThread() throws IOException
	{
        final UserSession session = new UserSession(m_systemUser, m_systemPassword);
       	m_userSessions.put(m_systemUser + ":::" + m_systemPassword, session);

       	Log.info("Authenticating system user");
		if (!session.authenticate())
		{
			throw new IOException("Unable to authenticate with system user and password");
		}
		
		new Thread("Torrents update thread")
		{
			public void run()
			{
				while(true)
				{
					try
					{
						updateTorrents(session);
					} catch (IOException e1)
					{
						Log.warn("IOException when updating torrents",e1);
					}
					
					try
					{
						Thread.sleep(m_updateInterval * 60 * 1000);
					} catch (InterruptedException e)
					{
					}
				}
			}
		}.start();
	}

	protected void updateTorrents(UserSession session) throws IOException
	{
		StringTokenizer tok = new StringTokenizer(m_updateCategories, ", ");
		while(tok.hasMoreElements())
		{
			String cat = tok.nextToken();
			try
			{
				updateCategory(cat, session);
			} catch (ParserException e)
			{
				Log.warn("Error parsing html of category " + cat,e);
			} 
		}
	}

	public String getRSS(UserSession session) throws FeedException
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
		Enumeration torrents = m_torrents.elements();
		while(torrents.hasMoreElements())
		{
			v.add(torrents.nextElement());
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
			entry.setLink(baseUrl + "/download?id=" + t.id + "&user=" + session.username + "&pass=" + session.password);
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

	private void updateCategory(String cat, UserSession session) throws ParserException, IOException 
	{
		URL url = new URL("http://www.torrentleech.org/browse.php?cat="+cat);
		Log.warn("Updating torrents, category = " + cat);
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", session.getCookiesString());
		processTorrentsStream(conn.getInputStream());
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

	
	private InputStream doGet(String u, UserSession session) throws IOException
	{
		URL url = new URL(u);
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", session.getCookiesString());
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
		
		public Torrent(String id)
		{
			this.id = id;
		}
		
		public String toString()
		{
			return id + " " + name + ", " + date + ", " + downloadLink;
		}
	}
	
	private static class UserSession
	{
		String username;
		String password;
		Properties m_cookies = new Properties();
		
		private boolean m_failedToAuthenticate;
		
		public UserSession(String username, String password)
		{
			super();
			this.username = username;
			this.password = password;
		}
		
		public boolean authenticate() throws IOException
		{
			if (m_failedToAuthenticate) return false;
			
			login();
			boolean success = isAuthenticated();
			if (!success)
			{
				m_failedToAuthenticate = true;
			}
			return success;
		}
		
		public boolean isAuthenticated()
		{
			String uid = m_cookies.getProperty("uid");
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
		
		public void login() throws IOException
		{
			String uip = getUIP();
			URL url = new URL("http://www.torrentleech.org/takelogin.php");
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.8.1.8) Gecko/20071004 Iceweasel/2.0.0.8 (Debian-2.0.0.8-1)");
			conn.setRequestProperty("Referer","http://www.torrentleech.org/login.php");
			conn.setRequestProperty("Cookie", getCookiesString());
			byte data[] = ("username="+username+"&password="+password+"&uip="+ uip).getBytes();
			conn.setRequestProperty("Content-Length", ""+data.length);
			OutputStream out = conn.getOutputStream();
			out.write(data);
			conn.setInstanceFollowRedirects(false);
			conn.connect();
			List cookies = (List) conn.getHeaderFields().get("Set-Cookie");
			handleCookies(cookies);
		}
		
		private String getUIP() throws IOException
		{
			URL url = new URL("http://www.torrentleech.org/login.php");
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			List cookies = (List) conn.getHeaderFields().get("Set-Cookie");
			handleCookies(cookies);
			BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String line;
			Pattern pattern = Pattern.compile("<input type=hidden name=uip value='(.*)'>");
			while((line = reader.readLine()) != null)
			{
				Matcher matcher = pattern.matcher(line);
				if (matcher.matches())
				{
					return matcher.group(1);
				}
			}
			return "";
		}
		
		private void handleCookies(List cookies)
		{
			for(int i=0;i<cookies.size();i++)
			{
				String s = (String) cookies.get(i);
				StringTokenizer t = new StringTokenizer(s, "="); // key=value;exp;location
				String key = t.nextToken();
				StringTokenizer toks = new StringTokenizer(t.nextToken(),";");
				String value = toks.nextToken();
				m_cookies.put(key, value);
			}
		}

		private String getCookiesString()
		{
			StringBuffer b = new StringBuffer();
			Enumeration keys = m_cookies.keys();
			while(keys.hasMoreElements())
			{
				String key = (String) keys.nextElement();
				String value = (String) m_cookies.get(key);
				b.append(key + "=" + value);
				if (keys.hasMoreElements())
				{
					b.append(";");
				}
			}
			return b.toString();
		}

		
	}
	
	public static void main(String[] args) throws Exception
	{
		File file = new File("conf.properties");
		if (!file.exists())
		{
			System.err.println("Missing conf.properties, copy rename sample-conf.properties and fill in the missing bits");
			return;
		}
		Properties props = new Properties();
		props.load(new FileInputStream(file));
		new TorrentLeech(props);
	}
	
}