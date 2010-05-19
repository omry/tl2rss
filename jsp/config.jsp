<%@page import="net.firefang.tl2rss.*"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Frameset//EN" "http://www.w3.org/TR/html4/frameset.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>TL2RSS 2.0</title>
</head>
<a href="/index.jsp">Back</a><br/>
<%
	if (request.getParameter("save") != null)
	{
		String cats = "";
		for(int i=0;i<47;i++)
		{
			String ss = request.getParameter("c" + i);
			if ("on".equalsIgnoreCase(ss))
			{
				if (cats.length() > 0) cats += ",";
				cats += "" + i;
			}
		}
		TorrentLeechRssServer.instance.setUpdateCategories(cats);
	}

%>
<%!

String cat(int id, String name)
{
	return cat1(id, name, true);
}

String cat1(int id, String name, boolean table)
{
	String s = "<input id=c"+id+" name=c"+id+" type=\"checkbox\" "+(TorrentLeechRssServer.instance.categoryActive(id) ? "\"checked\"" : "")+"><label title='"+name +" (id :"+id+")' for=\"c"+id+"\">"+name+"</label></a>";
	if (table)
		return "<td >"+s+"</td>";
	else return s;
} 
%>
<form method="POST" action=config.jsp>
	<%= cat1(0, "All categories", false) %><br/><br/>
	<table>
	<tr>
		<%= cat(27,"Anime/Cartoon") %>
		<%= cat(39,"Appz/MAC") %>
		<%= cat(22,"Appz/misc") %>
		<%= cat(1,"Appz/PC ISO") %>
	</tr><tr>
		<%= cat(32,"Appz/PDA") %>
		<%= cat(28,"Books - Mags") %>
		<%= cat(40,"Documentaries") %>
		<%= cat(33,"Episodes/Boxsets") %>
	</tr><tr>
		<%= cat(7,"Episodes/TV") %>
		<%= cat(4,"Games/PC ISO") %>
		<%= cat(41,"Games/PC Retro") %>
		<%= cat(21,"Games/PC Rips") %>
	</tr><tr>
		<%= cat(17,"Games/PS2") %>
		<%= cat(45,"Games/PS2 Retro") %>
		<%= cat(26,"Games/PSP") %>
		<%= cat(36,"Games/Wii") %>
	</tr><tr>
		<%= cat(44,"Games/X360 Retro") %>
		<%= cat(24,"Games/XBOX") %>
		<%= cat(10,"Games/XBOX360") %>
		<%= cat(20,"Movies/DBD-R") %>
	</tr><tr>
		<%= cat(43,"Movies/Foreign") %>
		<%= cat(35,"Movies/HD-x264") %>
		<%= cat(38,"Movies/Music DVD") %>
		<%= cat(42,"Movies/Retro") %>
	</tr><tr>
		<%= cat(19,"Movies/XviD") %>
		<%= cat(6,"Music") %>
		<%= cat(11,"Nintendo DS") %>
		<%= cat(47,"NonScene/BRRip-x264") %>
	</tr><tr>
		<%= cat(46,"NonScene/BRRip-XviD") %>
		<%= cat(48,"NonScene/Xvid") %>
	</tr>
	</table>
	<input type="hidden" name="save" value="true" />
	<input type="submit" value="Save" />
</form>	
</html>
