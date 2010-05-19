<%@page import="net.firefang.tl2rss.*"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN" "http://www.w3.org/TR/html4/frameset.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>TL2RSS 2.0</title>
</head>
<body>
	TL2RSS supports the following paramaters to the RSS feed:<br/>
	<ul>
		<li><b>cat=ID1,ID2</b><br/>
			Filter by category, for example : <a href="/rss?cat=7,9">/rss?cat=7,9</a><br/>
			Will return an RSS feed with only torrents from category 7 and 9.<br/></li>
		<li><b>max_per_cat=N</b><br/>
			Limit the maximum number of torrent from any given category, for example <a href="/rss?max_per_cat=2">/rss?max_per_cat=2</a></li>
	</ul>
	
	Notes: 
	<ul>
		<li>You can obtain category ides from the <a href="/config.jsp">config</a> page by hoveing above a specific category. the tooltip will contain the ID</li>
		<li>It's possible to combine multiple parameters by separating  them by &amp; ,for example  <a href="/rss?max_per_cat=2&cat=5">/rss?max_per_cat=2&amp;cat=7</a></li>
	</ul>
</body>
</html>