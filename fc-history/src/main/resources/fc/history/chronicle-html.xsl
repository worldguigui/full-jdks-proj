<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="html" encoding="UTF-8" indent="yes"/>
    <xsl:template match="/">
        <html>
            <head>
                <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
                <title>边境纪事 · 编年史</title>
                <style>
                    body { font-family: sans-serif; margin: 2em; color: #1b1b1b; }
                    h1 { border-bottom: 2px solid #31527a; padding-bottom: .3em; }
                    table { border-collapse: collapse; width: 100%; }
                    th { background: #31527a; color: white; text-align: left; }
                    th, td { border: 1px solid #b8c4d2; padding: .35em .55em; }
                    tr:nth-child(even) { background: #eef3f8; }
                    .meta { color: #5b6b7c; }
                </style>
            </head>
            <body>
                <h1>边境纪事 · 编年史</h1>
                <p class="meta">
                    事件数：<xsl:value-of select="count(/chronicle/event)"/>
                </p>
                <table>
                    <tr>
                        <th>星历</th>
                        <th>日期</th>
                        <th>类别</th>
                        <th>类型</th>
                        <th>地点</th>
                        <th>主体</th>
                        <th>原因</th>
                        <th>结果</th>
                    </tr>
                    <xsl:for-each select="/chronicle/event">
                        <tr>
                            <td><xsl:value-of select="@day"/></td>
                            <td><xsl:value-of select="@date"/></td>
                            <td><xsl:value-of select="@category"/></td>
                            <td><xsl:value-of select="@type"/></td>
                            <td><xsl:value-of select="@location"/></td>
                            <td><xsl:value-of select="actor"/></td>
                            <td><xsl:value-of select="cause"/></td>
                            <td><xsl:value-of select="outcome"/></td>
                        </tr>
                    </xsl:for-each>
                </table>
            </body>
        </html>
    </xsl:template>
</xsl:stylesheet>
