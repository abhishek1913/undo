import { motion, useScroll, useSpring } from 'motion/react';
import { Icon, useSmoothScroll } from './film';
import Opening from './chapters/Opening';
import { AsksTwice, LostAnswer } from './chapters/Repeats';
import Ticket from './chapters/Ticket';
import Crash from './chapters/Crash';
import Revert from './chapters/Revert';
import Drift from './chapters/Drift';
import { Finale, HowItWorks, Proof } from './chapters/Ending';

function Nav() {
  const { scrollYProgress } = useScroll();
  const x = useSpring(scrollYProgress, { stiffness: 120, damping: 30 });
  return (
    <>
      <motion.span className="progress" style={{ scaleX: x }} aria-hidden />
      <nav className="nav">
        <a className="logo" href="#top" aria-label="Undo, back to the start">
          <Icon name="undo" />
          Undo
        </a>
        <div className="nav-links">
          <a href="#story-1">Stories</a>
          <a href="#how">How it works</a>
          <a href="#proof">Proof</a>
        </div>
      </nav>
    </>
  );
}

export default function App() {
  useSmoothScroll();
  return (
    <>
      <Nav />
      <main>
        <Opening />
        <LostAnswer />
        <AsksTwice />
        <Ticket />
        <Crash />
        <Revert />
        <Drift />
        <HowItWorks />
        <Proof />
      </main>
      <Finale />
    </>
  );
}
