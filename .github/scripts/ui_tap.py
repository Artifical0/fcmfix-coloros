"""Print the centre of the first node in a uiautomator dump whose text or content-desc is argv[2]."""
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if sys.argv[2] in (node.get("text"), node.get("content-desc")):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        print((left + right) // 2, (top + bottom) // 2)
        sys.exit(0)
sys.exit("not on screen: " + sys.argv[2])
