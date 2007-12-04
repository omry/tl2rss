package net.firefang.tl2rss;

import org.htmlparser.Tag;
import org.htmlparser.Text;
import org.htmlparser.visitors.NodeVisitor;

public class TlVisitor extends NodeVisitor 
{
	private boolean m_inTable;

	/**
	 * @see org.htmlparser.visitors.NodeVisitor#visitTag(org.htmlparser.Tag)
	 */
	public void visitTag(Tag tag)
	{
		String tagName = tag.getTagName();
		if (tagName.equalsIgnoreCase("table"))
		{
			m_inTable = true;
		}
		
		if (m_inTable)
		{
			System.err.println(tag.toString());
		}
	}

	/**
	 * @see org.htmlparser.visitors.NodeVisitor#visitStringNode(org.htmlparser.Text)
	 */
	public void visitStringNode(Text text)
	{
	}

	/**
	 * @see org.htmlparser.visitors.NodeVisitor#visitEndTag(org.htmlparser.Tag)
	 */
	public void visitEndTag(Tag tag)
	{
		String tagName = tag.getTagName();
		if (tagName.equalsIgnoreCase("table"))
		{
			m_inTable = false;
		}		
	}
}
