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
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
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
import org.htmlparser.util.NodeIterator;
import org.htmlparser.util.NodeList;
import org.htmlparser.util.ParserException;

public class TorrentLeech
{
	private String m_user = "";
	private String m_password = "";
	private Properties m_cookies = new Properties();
	
	public static void main(String[] args) throws IOException, ParserException
	{
		Properties props = new Properties();
		props.load(new FileInputStream("conf.properties"));
		TorrentLeech tl = new TorrentLeech(props.getProperty("user"), props.getProperty("pass"));
		tl.test();
//		tl.login();
//		tl.updateCategory(7);
	}
	
	public TorrentLeech(String user, String password)
	{
		m_user = user;
		m_password = password;
	}
	
	private void test() throws FileNotFoundException, ParserException, UnsupportedEncodingException
	{
		Parser parser = new Parser(new Lexer(new Page(new FileInputStream("test.html"), "UTF-8")), Parser.DEVNULL);
		NodeList res = parser.extractAllNodesThatMatch(getDownloadsFilter());
		for (int i = 0; i < res.size(); i++)
		{
			System.err.println(res.elementAt(i).toHtml());
		}
	}

	private void updateCategory(int cat) throws ParserException, IOException 
	{
		URL url = new URL("http://www.torrentleech.org/browse.php?cat="+cat);
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
	}

	public void login() throws IOException
	{
		String uip = getUIP();
		URL url = new URL("http://www.torrentleech.org/takelogin.php");
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setInstanceFollowRedirects(false);
		conn.setDoOutput(true);
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.8.1.8) Gecko/20071004 Iceweasel/2.0.0.8 (Debian-2.0.0.8-1)");
		conn.setRequestProperty("Referer","http://www.torrentleech.org/login.php");
		conn.setRequestProperty("Cookie", getCookiesString());
		byte data[] = ("username="+m_user+"&password="+m_password+"&uip="+ uip).getBytes();
		conn.setRequestProperty("Content-Length", ""+data.length);
		OutputStream out = conn.getOutputStream();
		out.write(data);
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
}