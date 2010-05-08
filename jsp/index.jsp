<%@page import="net.firefang.tl2rss.*"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN" "http://www.w3.org/TR/html4/frameset.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>TL2RSS 2.0</title>
</head>
	<% if (!TorrentLeechRssServer.instance.isAuthenticated())
	{
%>
<a href="/proxy/login.php">Log into TorrentLeech</></a><br/>
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
	<a href="/rss">RSS feed</><br/>
	<a href="/config.jsp">Configure TL2RSS</></a><br/>
</html>
