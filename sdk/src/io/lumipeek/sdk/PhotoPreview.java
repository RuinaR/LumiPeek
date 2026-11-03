package io.lumipeek.sdk;

import io.lumipeek.core.*;
import java.awt.*;
import java.awt.image.*;
import java.util.Arrays;
import java.util.List;
import javax.swing.*;

/** RGB-only own preview. Desktop construction is blocked until input transparency is verified. */
final class PhotoPreview implements PhotoSession.Preview {
    static final class Surface extends JPanel {
        private static final long serialVersionUID=1L;
        private transient BufferedImage image;
        private transient PreparedPhoto selected;
        private boolean dots;
        Surface() { setFocusable(false); setPreferredSize(new Dimension(196,220)); setVisible(false); }
        void display(PreparedPhoto photo, boolean thinking, int pixels) {
            if(photo!=selected || image==null || Math.max(image.getWidth(),image.getHeight())!=pixels) {
                clear(); byte[] rgb=photo.copyRgb();
                try {
                    double scale=pixels/(double)Math.max(photo.width(),photo.height());
                    int w=Math.max(1,(int)Math.round(photo.width()*scale)),h=Math.max(1,(int)Math.round(photo.height()*scale));
                    image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                    int[] target=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
                    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                        int source=(Math.min(photo.height()-1,y*photo.height()/h)*photo.width()+Math.min(photo.width()-1,x*photo.width()/w))*3;
                        target[y*w+x]=((rgb[source]&255)<<16)|((rgb[source+1]&255)<<8)|(rgb[source+2]&255);
                    }
                    selected=photo;
                } finally { Arrays.fill(rgb,(byte)0); }
            }
            dots=thinking; setVisible(true); repaint();
        }
        void clear() {
            if(image!=null) { Arrays.fill(((DataBufferInt)image.getRaster().getDataBuffer()).getData(),0); image.flush(); }
            image=null; selected=null; dots=false; setVisible(false); repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if(image==null) return;
            int x=(getWidth()-image.getWidth())/2;
            graphics.drawImage(image,x,8,null);
            if(dots) { graphics.setColor(getForeground()); graphics.drawString("···",Math.max(8,getWidth()/2-7),image.getHeight()+28); }
        }
        Dimension photoSize() { return image==null?new Dimension():new Dimension(image.getWidth()+16,image.getHeight()+40); }
    }
    final Surface inline=new Surface();
    private final PreviewFollow follow=new PreviewFollow();
    private JWindow desktop;
    private Surface floating;
    private boolean enabled;
    void enabled(boolean value) { enabled=value; if(!value) hide(); }
    void createDesktop() {
        if(!SdkCompatibility.DESKTOP_PREVIEW_VERIFIED) throw new IllegalStateException("INPUT_TRANSPARENCY_NOT_VERIFIED");
        desktop=new JWindow(); desktop.setFocusableWindowState(false); desktop.setAutoRequestFocus(false);
        desktop.setAlwaysOnTop(false); floating=new Surface(); desktop.setContentPane(floating);
    }
    void follow(Rectangle actor, boolean right, List<Rectangle> screens, double elapsed) {
        if(desktop==null || floating==null) return;
        var placement=follow.update(actor,right,screens,floating.photoSize(),elapsed);
        if(placement.isEmpty()) { desktop.setVisible(false); return; }
        Rectangle previous=desktop.getBounds(), next=placement.get();
        if(!previous.intersects(next)) desktop.setVisible(false);
        desktop.setBounds(next); desktop.setVisible(true);
    }
    @Override public void follow(com.group_finity.mascot.lumi.plugin.MascotState actor, List<Rectangle> screens, double elapsed) {
        follow(actor.bounds(),actor.lookRight(),screens,elapsed);
    }
    @Override public void show(PreparedPhoto photo, boolean dots, int pixels) {
        if(!enabled) { hide(); return; }
        inline.display(photo,dots,pixels); if(floating!=null) floating.display(photo,dots,pixels);
    }
    @Override public void hide() { inline.clear(); follow.reset(); if(floating!=null) floating.clear(); if(desktop!=null) desktop.setVisible(false); }
    @Override public void close() {
        Runnable cleanup=() -> { hide(); if(desktop!=null) desktop.dispose(); desktop=null; floating=null; };
        if(SwingUtilities.isEventDispatchThread() || GraphicsEnvironment.isHeadless()) cleanup.run(); else SwingUtilities.invokeLater(cleanup);
    }
}
