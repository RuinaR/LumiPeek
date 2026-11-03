package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.*;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.event.*;

/** Explicit user actions only. No model, file chooser or network operation runs during construction. */
final class SettingsPanel extends JPanel {
    private static final long serialVersionUID=1L;
    final DefaultListModel<PhotoSession.Folder> folderModel=new DefaultListModel<>();
    final JList<PhotoSession.Folder> folders=new JList<>(folderModel);
    final JCheckBox recursive=new JCheckBox("하위 폴더도 검색"), automatic=new JCheckBox("자동 감상 (이번 실행만)"),
        thinking=new JCheckBox("분석 중 ··· 표시"), preview=new JCheckBox("이 설정 창에서 사진 미리보기"), paused=new JCheckBox("AI 분석 일시정지");
    final JSpinner interval=new JSpinner(new SpinnerNumberModel(60,5,240,5));
    final JSlider size=new JSlider(96,180,144);
    final JComboBox<ModelCatalog.Entry> model=new JComboBox<>(ModelCatalog.ENTRIES.toArray(ModelCatalog.Entry[]::new));
    final JLabel status=new JLabel("준비 중"), installed=new JLabel("연결 확인 전 · 선택 모델의 설치 여부 미확인");
    final JButton add=new JButton("폴더 추가"), remove=new JButton("제거"), connect=new JButton("연결 확인"),
        download=new JButton("모델 다운로드…"), look=new JButton("사진 한 장 구경하기"), cancel=new JButton("현재 작업 취소");
    private final transient PhotoSession session;
    private boolean updating;
    SettingsPanel(PluginContext context, PhotoSession session, PhotoPreview surface) {
        this.session=session;
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS)); setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        folders.setVisibleRowCount(3); folders.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JLabel("허용 폴더 · 읽기 전용 · 이번 실행에만 유지")); add(new JScrollPane(folders)); add(row(add,remove));
        add(row(recursive,automatic)); add(row(new JLabel("자동 감상 간격 (분)"),interval));
        size.setMajorTickSpacing(28); size.setPaintLabels(true); add(row(new JLabel("사진 크기"),size));
        add(row(thinking,preview)); add(new JLabel("데스크톱 사진 창·공유 busy: 입력 통과·소유권 검증 전 차단"));
        add(row(new JLabel("비전 AI"),model)); add(installed); add(row(connect,download,paused));
        add(new JLabel("로컬 Ollama 127.0.0.1:11434 · 외부 주소 불가 · 서버 직접 실행 필요"));
        add(new JLabel("Ollama 미설치·미실행 시 직접 준비해 주세요. 호환 cloud-OFF 상태 API가 필요합니다."));
        add(new JLabel("추론은 메모리·VRAM을 사용합니다. 사용 중인 작업의 여유를 확인해 주세요."));
        add(new JLabel("일반 사진 감상용 · 공개판 성인 AI 반응은 비활성 · 모델 거절 시 생략"));
        add(surface.inline); add(status); add(row(look,cancel));
        var theme=context.theme();
        for(JButton button:new JButton[]{add,remove,connect,download,look,cancel}) theme.button(button,PluginTheme.Button.OUTLINE);
        for(JCheckBox box:new JCheckBox[]{recursive,automatic,thinking,preview,paused}) theme.toggle(box);
        theme.slider(size,false); theme.dress(this,() -> setBackground(theme.cardBackground()));
        theme.colour(status,theme::inkSoft);
        add.addActionListener(e -> {
            JFileChooser chooser=new JFileChooser(); chooser.setDialogTitle("LumiPeek가 읽을 사진 폴더 선택");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setAcceptAllFileFilterUsed(false);
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) addFolder(chooser.getSelectedFile().toPath().toAbsolutePath().normalize());
        });
        remove.addActionListener(e -> { var selected=folders.getSelectedValue(); if(selected!=null) session.removeFolder(selected.id()); });
        automatic.addActionListener(e -> { if(!updating) session.automatic(automatic.isSelected()); });
        paused.addActionListener(e -> { if(!updating) session.pause(paused.isSelected()); });
        recursive.addActionListener(e -> apply()); thinking.addActionListener(e -> apply()); interval.addChangeListener(e -> apply());
        size.addChangeListener(e -> { if(!size.getValueIsAdjusting()) apply(); }); model.addActionListener(e -> apply());
        preview.addActionListener(e -> surface.enabled(preview.isSelected()));
        connect.addActionListener(e -> session.connect()); look.addActionListener(e -> session.request(null,null)); cancel.addActionListener(e -> session.cancel());
        download.addActionListener(e -> {
            var selected=(ModelCatalog.Entry)model.getSelectedItem();
            if(selected!=null && JOptionPane.showConfirmDialog(this,selected.consentText(),"공식 모델 다운로드 동의",JOptionPane.YES_NO_OPTION,JOptionPane.INFORMATION_MESSAGE)==JOptionPane.YES_OPTION)
                session.download(selected.tag(),true);
        });
        session.listen(this::update);
    }
    private static JPanel row(Component... components) { JPanel panel=new JPanel(new FlowLayout(FlowLayout.LEFT,8,4)); panel.setOpaque(false); for(Component c:components) panel.add(c); return panel; }
    void addFolder(Path root) { session.addFolder(root); }
    private void apply() {
        if(updating) return;
        var selected=(ModelCatalog.Entry)model.getSelectedItem();
        if(selected!=null) session.configure(new PhotoSettings(recursive.isSelected(),((Number)interval.getValue()).intValue(),size.getValue(),thinking.isSelected(),selected.tag()));
    }
    private void update(PhotoSession.Status state) {
        updating=true;
        try {
            var selected=folders.getSelectedValue(); folderModel.clear(); state.folders().forEach(folderModel::addElement); if(selected!=null) folders.setSelectedValue(selected,true);
            recursive.setSelected(state.settings().recursive()); interval.setValue(state.settings().intervalMinutes()); size.setValue(state.settings().previewPixels());
            thinking.setSelected(state.settings().thinking()); model.setSelectedItem(ModelCatalog.require(state.settings().model()));
            automatic.setSelected(state.automatic()); paused.setSelected(state.paused());
            boolean editable=!state.busy() && !state.blocked();
            for(JComponent c:new JComponent[]{recursive,interval,size,thinking,model,add,connect,download}) c.setEnabled(editable);
            look.setEnabled(editable && !state.paused()); cancel.setEnabled(state.busy()); remove.setEnabled(!state.folders().isEmpty());
            boolean present=state.models().stream().anyMatch(m -> m.name().equals(state.settings().model()));
            installed.setText(present?"선택 모델 설치됨 · 추론 시 로컬 비전 메타데이터 재검사":"선택 모델 미확인 · 연결 확인 또는 다운로드 필요");
            status.setText(state.message());
        } finally { updating=false; }
    }
}
