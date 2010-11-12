<%@page import="net.firefang.tl2rss.*"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN" "http://www.w3.org/TR/html4/frameset.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>TL2RSS 3.0</title>
</head>
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
</html>