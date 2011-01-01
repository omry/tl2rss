<%@page import="net.firefang.tl2rss.*"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN" "http://www.w3.org/TR/html4/frameset.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>TL2RSS 3.0.2</title>
</head>
<body>

<h3>Support TL2RSS</h3>
If you like TL2RSS Show it by making a small donation. even $5 will motivate me to continue supporting this tool.
	<form action="https://www.paypal.com/cgi-bin/webscr" method="post">
	<input type="hidden" name="cmd" value="_s-xclick">
	<input type="hidden" name="hosted_button_id" value="JEDVW2YRYCA3C">
	<input type="image" src="https://www.paypal.com/en_US/i/btn/btn_donate_LG.gif" border="0" name="submit" alt="PayPal - The safer, easier way to pay online!">
	<img alt="" border="0" src="https://www.paypal.com/en_US/i/scr/pixel.gif" width="1" height="1">
	</form>
	<br/>

<h3>Actions</h3>
	<% if (!TorrentLeechRssServer.instance.isAuthenticated())
	{
%>
<a href="/proxy/user/account/login/">Log into TorrentLeech</></a><br/>
<%
	}
	else
	{
%>
<a href="/logout">Log out of TorrentLeedch</></a><br/>
<%
	}
	%>
	<br/>
	<a href="/rss">RSS feed</>&nbsp;&nbsp;&nbsp;&nbsp;<a href="help.jsp">(Feed parameters help)</a><br/>
	<a href="/config.jsp">Configure TL2RSS</></a><br/>
	
<h3>System information</h3>	
	
	<table>
		<th>
			<td>
				Name
			</td>
			<td>
				Value
			</td>
		</th>
		
		<tr>
			<td>
				<b>Active categories</b>
			</td>
			<td>
				<%= TorrentLeechRssServer.instance.getUpdatedCategories()%>
			</td>
		</tr>		
		<tr>
			<td>
				<b>Torrents count</b>
			</td>
			<td>
				<%= TorrentLeechRssServer.instance.numTorrents()%>
			</td>
		</tr>		
	</table>
</body>
</html>