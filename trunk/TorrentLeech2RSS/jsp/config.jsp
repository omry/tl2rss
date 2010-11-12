<%@page import="net.firefang.tl2rss.*"%>
<%@page import="java.util.*"%>
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
	<%
		Iterator<CategoryGroup> groups = TorrentLeechRssServer.instance.catGroups();
		while(groups.hasNext())
		{
			CategoryGroup group = groups.next();
			%>
			<tr>
			<td><%=group.name()%></td>
			<%
			
			for(Category c : group.cats())
			{
				%>
				<td><%=cat(c.id())%></td>
				<%
			}
			
			%>
			</tr>
			<%
		}
	%>
	</table>
	<input type="hidden" name="save" value="true" />
	<input type="submit" value="Save" />
</form>	
</html>
