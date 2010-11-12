/**
 * 
 */
package net.firefang.tl2rss;

import java.util.ArrayList;
import java.util.List;


public class CategoryGroup
{
	String name;
	List<Category> categories;
	public CategoryGroup(String name) 
	{
		this.name = name;
		categories = new ArrayList<Category>();
	}
	public String name() {return name;}
	@Override
	public String toString() 
	{
		return name + " : " + categories;
	}
	
	public List<Category> cats() {return categories;}
}