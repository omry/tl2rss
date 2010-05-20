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

String cat(int id)
{
	String name = TorrentLeechRssServer.instance.getCategory(id);
	return cat1(id, name, true); 
}

String cat1(int id, String name, boolean table)
{
	String s = "<input id=c"+id+" name=c"+id+" type=\"checkbox\" "+(TorrentLeechRssServer.instance.categoryActive(id) ? "checked" : "")+"><label title='"+name +" (id :"+id+")' for=\"c"+id+"\">"+name+"</label></a>";
	if (table)
		return "<td >"+s+"</td>";
	else return s;
} 
%>
<form method="POST" action=config.jsp>
	<%= cat1(0, "All Categories", false) %><br/><br/>
	<table>
	<tr>
		<%= cat(27) %>
		<%= cat(39) %>
		<%= cat(22) %>
		<%= cat(1 ) %>
	</tr><tr>
		<%= cat(32) %>
		<%= cat(28) %>
		<%= cat(40) %>
		<%= cat(33) %>
	</tr><tr>
		<%= cat(7 ) %>
		<%= cat(4 ) %>
		<%= cat(41) %>
		<%= cat(21) %>
	</tr><tr>
		<%= cat(17) %>
		<%= cat(45) %>
		<%= cat(26) %>
		<%= cat(36) %>
	</tr><tr>
		<%= cat(44) %>
		<%= cat(24) %>
		<%= cat(10) %>
		<%= cat(20) %>
	</tr><tr>
		<%= cat(43) %>
		<%= cat(35) %>
		<%= cat(38) %>
		<%= cat(42) %>
	</tr><tr>
		<%= cat(19) %>
		<%= cat(6 ) %>
		<%= cat(11) %>
		<%= cat(47) %>
	</tr><tr>
		<%= cat(46) %>
		<%= cat(48) %>
	</tr>
	</table>
	<input type="hidden" name="save" value="true" />
	<input type="submit" value="Save" />
</form>	
</html>
