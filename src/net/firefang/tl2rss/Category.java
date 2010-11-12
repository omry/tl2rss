/**
 * 
 */
package net.firefang.tl2rss;

public class Category
{
	public Category(int category, String desc) 
	{
		id = category;
		name = desc;
	}
	int id;
	String name;
	
	@Override
	public String toString() 
	{
		return name + " ("+id+")";
	}
	
	public int id() {return id;}
}