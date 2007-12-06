package net.firefang.tl2rss;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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

import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndEntryImpl;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.feed.synd.SyndFeedImpl;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedOutput;
import com.sun.syndication.io.impl.DateParser;

public class TorrentLeech
{
	private String m_user = "";
	private String m_password = "";
	private Properties m_cookies = new Properties();
	
	private Hashtable m_torrents = new Hashtable();
	
	public static void main(String[] args) throws IOException, ParserException, FeedException
	{
		Properties props = new Properties();
		props.load(new FileInputStream("conf.properties"));
		TorrentLeech tl = new TorrentLeech(props.getProperty("user"), props.getProperty("pass"));
		tl.test();
//		tl.login();
//		tl.updateCategory(7);
		
		System.err.println(tl.getRSS());
	}
	
	public String getRSS() throws FeedException
	{
		String feedType = "rss_2.0";
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType(feedType);

		feed.setTitle("TorrentLeech RSS Feed");
		feed.setLink("http://TODO!"); //TODO: put actual unique link
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
					return d1.compareTo(d2);
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
			entry.setLink(t.downloadLink);
			entry.setPublishedDate(DateParser.parseDate(t.date));
			entries.add(entry);
		}

		
		SyndFeedOutput output = new SyndFeedOutput();
		return output.outputString(feed);
	}

	public TorrentLeech(String user, String password)
	{
		m_user = user;
		m_password = password;
	}
	
	private void test() throws FileNotFoundException, ParserException, UnsupportedEncodingException
	{
		processTorrentsStream(new FileInputStream("test.html"));

	}

	private Torrent getTorrent(String id)
	{
		if (!m_torrents.containsKey(id))
		{
			m_torrents.put(id, new Torrent(id));
		}
		
		return (Torrent) m_torrents.get(id);
	}

	private void updateCategory(int cat) throws ParserException, IOException 
	{
		URL url = new URL("http://www.torrentleech.org/browse.php?cat="+cat);
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
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

	public void login() throws IOException
	{
		String uip = getUIP();
		URL url = new URL("http://www.torrentleech.org/takelogin.php");
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setDoOutput(true);
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.8.1.8) Gecko/20071004 Iceweasel/2.0.0.8 (Debian-2.0.0.8-1)");
		conn.setRequestProperty("Referer","http://www.torrentleech.org/login.php");
		conn.setRequestProperty("Cookie", getCookiesString());
		byte data[] = ("username="+m_user+"&password="+m_password+"&uip="+ uip).getBytes();
		conn.setRequestProperty("Content-Length", ""+data.length);
		OutputStream out = conn.getOutputStream();
		out.write(data);
		conn.setInstanceFollowRedirects(false);
		conn.connect();
		List cookies = (List) conn.getHeaderFields().get("Set-Cookie");
		handleCookies(cookies);
	}
	
	private InputStream doGet(String u) throws IOException
	{
		URL url = new URL(u);
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
		return conn.getInputStream();
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
}