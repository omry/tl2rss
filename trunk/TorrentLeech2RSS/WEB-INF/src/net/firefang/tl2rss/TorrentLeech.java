package net.firefang.tl2rss;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.htmlparser.Parser;
import org.htmlparser.lexer.Lexer;
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
	
	private void test() throws FileNotFoundException, ParserException
	{
		Parser parser = new Parser("file:///home/omry/1.html");
		TlVisitor tlv = new TlVisitor();
		parser.visitAllNodesWith(tlv);
	}

	private void updateCategory(int cat) throws IOException, ParserException
	{
		URL url = new URL("http://www.torrentleech.org/browse.php?cat="+cat);
		URLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Cookie", getCookiesString());
		Parser parser = new Parser(conn);
		TlVisitor tlv = new TlVisitor();
		parser.visitAllNodesWith(tlv);
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
}